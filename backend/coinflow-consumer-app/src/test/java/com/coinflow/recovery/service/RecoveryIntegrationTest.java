package com.coinflow.recovery.service;

import com.coinflow.domain.aggregation.domain.vo.ClosedKlineSnapshot;
import com.coinflow.domain.aggregation.domain.vo.KlineSnapshot;
import com.coinflow.domain.aggregation.service.KlineAggregatorService;
import com.coinflow.domain.ohlc.domain.Ohlc1m;
import com.coinflow.domain.ohlc.repository.Ohlc1mRepository;
import com.coinflow.domain.ohlc.repository.OhlcWindowRepository;
import com.coinflow.domain.ohlc.service.*;
import com.coinflow.domain.recovery.domain.*;
import com.coinflow.domain.recovery.repository.*;
import com.coinflow.domain.recovery.service.*;
import com.coinflow.domain.symbol.domain.Symbol;
import com.coinflow.domain.symbol.domain.vo.MarketType;
import com.coinflow.domain.symbol.repository.SymbolRepository;
import com.coinflow.domain.symbol.service.SymbolService;
import com.coinflow.recovery.redis.RecoveryMaintenanceWorker;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.connection.stream.*;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.test.context.ActiveProfiles;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(properties = {"spring.data.redis.port=${COINFLOW_TEST_REDIS_PORT:16379}",
        "coinflow.recovery.maintenance.enabled=false"})
@ActiveProfiles("test")
@EnabledIfEnvironmentVariable(named = "COINFLOW_TEST_REDIS_PORT", matches = "\\d+")
class RecoveryIntegrationTest {
    @org.springframework.test.context.DynamicPropertySource
    static void database(org.springframework.test.context.DynamicPropertyRegistry registry) {
        String url = System.getenv("COINFLOW_TEST_DB_URL");
        if (url != null) {
            registry.add("spring.datasource.url", () -> url);
            registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
            registry.add("spring.datasource.username", () -> "recovery_test");
            registry.add("spring.datasource.password", () -> "recovery_test");
            registry.add("spring.jpa.database-platform", () -> "org.hibernate.dialect.PostgreSQLDialect");
            registry.add("spring.jpa.properties.hibernate.dialect", () -> "org.hibernate.dialect.PostgreSQLDialect");
        }
    }
    @Autowired RecoveryLedger ledger;
    @Autowired ConsumerCheckpointService checkpoints;
    @Autowired ConsumerCheckpointRepository checkpointRows;
    @Autowired RecoveryCandleStore candles;
    @Autowired FailedRecordRepository failures;
    @Autowired VerifiedCandleRepository verified;
    @Autowired SymbolRepository symbols;
    @Autowired SymbolService symbolService;
    @Autowired Ohlc1mRepository minuteRows;
    @Autowired Ohlc1mService minute;
    @Autowired Ohlc5mService five;
    @Autowired Ohlc30mService thirty;
    @Autowired OhlcWindowRepository window;
    @Autowired @org.springframework.beans.factory.annotation.Qualifier("redisTemplate") RedisTemplate<String, String> redis;
    @Autowired RedisTemplate<String, byte[]> rawRedisTemplate;
    @Autowired com.coinflow.config.properties.TickConsumerProperties properties;
    @Autowired FailedRecordService failedRecords;
    @Autowired com.fasterxml.jackson.databind.ObjectMapper mapper;
    private RecoveryMaintenanceService maintenance;
    private Symbol symbol;
    private final String stream = "test-stream";
    private final String group = "test-group";

    @BeforeEach void setup() {
        ledger.initialize();
        ledger.locked(cp -> {
            cp.setRecordId("0-0"); cp.setSnapshot(null); cp.setOwner(null); cp.setLeaseUntil(0);
            cp.setStreamKey(stream); cp.setConsumerGroup(group); return null;
        });
        verified.deleteAll(); failures.deleteAll(); minuteRows.deleteAll();
        symbol = symbols.findBySymbol("btcusdt").orElseGet(() -> symbols.save(Symbol.builder()
                .symbol("btcusdt").exchange("binance").name("Bitcoin").active(true).marketType(MarketType.SPOT).build()));
        redis.delete(List.of(stream, stream + ":dlq", stream + ":dlq:index"));
        maintenance = new RecoveryMaintenanceService(ledger, failures, verified, new com.coinflow.recovery.redis.RedisRecoveryStreamRepository(redis), window,
                symbolService, minute, five, thirty);
        checkpoints.acquire();
    }

    @AfterEach void cleanup() {
        checkpoints.release();
        redis.delete(List.of(stream, stream + ":dlq", stream + ":dlq:index",
                "klines:window:btcusdt:M1", "kline:live:btcusdt:M1"));
    }

    @Test void candleFailureRollsBackCheckpointAndEarlierCandleWrites() {
        var updates = List.of(new ConsumerCheckpointService.Candle("btcusdt", candidate(60, 1)),
                new ConsumerCheckpointService.Candle("missing-symbol", candidate(120, 2)));
        assertThrows(RuntimeException.class, () -> checkpoints.commit("2-0", state(), updates));
        assertEquals("0-0", checkpointRows.findById(1L).orElseThrow().getRecordId());
        assertEquals(0, minuteRows.count());
    }

    @Test void verifiedCandleCannotBeOverwrittenByDelayedConsumerCommit() {
        Ohlc1m repaired = Ohlc1m.builder().symbol(symbol).bucketTime(time(60))
                .open(BigDecimal.TEN).high(BigDecimal.TEN).low(BigDecimal.TEN).close(BigDecimal.TEN).volume(999L).build();
        candles.saveVerified(repaired);
        checkpoints.commit("2-0", state(), List.of(new ConsumerCheckpointService.Candle("btcusdt", candidate(60, 1))));
        assertEquals(999L, minuteRows.findBySymbolIdAndBucketTime(symbol.getId(), time(60)).orElseThrow().getVolume());
        assertEquals("2-0", checkpointRows.findById(1L).orElseThrow().getRecordId());
        maintenance.publishRepairs();
        assertTrue(verified.findById("btcusdt:M1:60").orElseThrow().isCachePublished());
    }

    @Test void ackLossIsRecoveredInPagesWithoutRemovingUnrepairedFailures() {
        for (int i = 1; i <= 510; i++) redis.opsForStream().add(StreamRecords.string(Map.of("p", "test"))
                .withStreamKey(stream).withId(RecordId.of(i + "-0")));
        readAll();
        FailedRecord failed = failure("1-0", "btcusdt:M1:60,btcusdt:M5:0");
        failures.save(failed);
        checkpoints.commit("510-0", state(), List.of());
        assertTrue(maintenance.cleanCheckpointPending());
        assertFalse(maintenance.cleanCheckpointPending());
        assertEquals(1, redis.opsForStream().pending(stream, group).getTotalPendingMessages());
        marker("btcusdt:M1:60");
        maintenance.resolveFailures();
        assertEquals(FailedRecord.Status.WAITING_REPAIR, failures.findById(failed.getId()).orElseThrow().getStatus());
        marker("btcusdt:M5:0");
        maintenance.resolveFailures();
        assertEquals(FailedRecord.Status.RESOLVED, failures.findById(failed.getId()).orElseThrow().getStatus());
        assertEquals(0, redis.opsForStream().pending(stream, group).getTotalPendingMessages());
        maintenance.trimRecoverableHistory();
        assertEquals(1, redis.opsForStream().size(stream)); // Checkpoint anchor is kept.
    }

    @Test void repairAckFailureRemainsAckPendingAndCanBeRetried() {
        FailedRecord failed = failure("1-0", "btcusdt:M1:60");
        failures.save(failed); marker("btcusdt:M1:60");
        redis.opsForValue().set(stream, "wrong-type");
        maintenance.resolveFailures(); // Redis command fails; completion must remain retryable.
        assertEquals(FailedRecord.Status.ACK_PENDING, failures.findById(failed.getId()).orElseThrow().getStatus());
        redis.delete(stream);
        redis.opsForStream().add(StreamRecords.string(Map.of("p", "test")).withStreamKey(stream).withId(RecordId.of("1-0")));
        readAll();
        maintenance.resolveFailures();
        maintenance.resolveFailures();
        assertEquals(FailedRecord.Status.RESOLVED, failures.findById(failed.getId()).orElseThrow().getStatus());
        assertEquals(0, redis.opsForStream().pending(stream, group).getTotalPendingMessages());
    }

    @Test void restartReplaysAckedAndPendingRecordsAfterTheCheckpoint() {
        var aggregate = new KlineAggregatorService();
        aggregate.processTickAndGetResult("btcusdt", BigDecimal.TEN, BigDecimal.ONE, 60_000);
        checkpoints.commit("1-0", new ConsumerCheckpointService.State(1, aggregate.checkpoint(), Map.of()), List.of());
        for (int i = 1; i <= 3; i++) {
            byte[] payload = com.coinflow.tick.serialization.TickRawBinaryCodec.encode("btcusdt", BigDecimal.TEN, BigDecimal.ONE, 60_000 + i);
            rawRedisTemplate.opsForStream().add(StreamRecords.newRecord().in(stream).ofMap(Map.of("p", payload)).withId(RecordId.of(i + "-0")));
        }
        readAll();
        redis.opsForStream().acknowledge(stream, group, "2-0"); // Lost memory even though this record was ACKed.
        var restarted = new KlineAggregatorService();
        var ticks = new com.coinflow.aggregation.service.TickProcessService(restarted, checkpoints,
                org.mockito.Mockito.mock(CandleProjectionPublisher.class),
                org.mockito.Mockito.mock(com.coinflow.aggregation.service.BatchAckWorker.class),
                org.mockito.Mockito.mock(com.coinflow.config.ConsumerApplicationShutdown.class),
                org.mockito.Mockito.mock(com.coinflow.monitoring.MetricRecorder.class));
        var handler = new com.coinflow.handler.TickRawMessageHandler(ticks, failedRecords);
        @SuppressWarnings("unchecked")
        var worker = (org.springframework.beans.factory.ObjectProvider<RecoveryMaintenanceWorker>)
                org.mockito.Mockito.mock(org.springframework.beans.factory.ObjectProvider.class);
        new StreamRecoveryService(rawRedisTemplate, properties, ticks, handler, failures, failedRecords, worker).recover();
        assertEquals("3-0", checkpointRows.findById(1L).orElseThrow().getRecordId());
        assertEquals(0, BigDecimal.valueOf(3).compareTo(restarted.checkpoint().active().get("btcusdt:M1").volume()));
        var restored = checkpoints.acquire();
        assertEquals(restarted.checkpoint(), restored.state().aggregate());
        maintenance.cleanCheckpointPending();
        assertEquals(0, redis.opsForStream().pending(stream, group).getTotalPendingMessages());
        ticks.close();
    }

    @Test void expiredOwnerCannotCommitAfterAnotherConsumerTakesOver() {
        var replacement = new ConsumerCheckpointService(ledger, candles, mapper, properties);
        assertThrows(IllegalStateException.class, replacement::acquire);
        ledger.locked(cp -> { cp.setLeaseUntil(0); return null; });
        replacement.acquire();
        assertThrows(IllegalStateException.class, () -> checkpoints.commit("1-0", state(), List.of()));
        assertEquals("0-0", checkpointRows.findById(1L).orElseThrow().getRecordId());
        replacement.release();
    }

    @Test void inFlightConsumerWriteWaitsForRepairAndCannotOverwriteIt() throws Exception {
        var pool = java.util.concurrent.Executors.newFixedThreadPool(2);
        var repairWritten = new java.util.concurrent.CountDownLatch(1);
        var allowCommit = new java.util.concurrent.CountDownLatch(1);
        var consumerStarted = new java.util.concurrent.CountDownLatch(1);
        try {
            var repair = pool.submit(() -> ledger.locked(cp -> {
                candles.saveVerified(Ohlc1m.builder().symbol(symbol).bucketTime(time(60))
                        .open(BigDecimal.TEN).high(BigDecimal.TEN).low(BigDecimal.TEN).close(BigDecimal.TEN).volume(999L).build());
                repairWritten.countDown();
                try { assertTrue(allowCommit.await(5, java.util.concurrent.TimeUnit.SECONDS)); }
                catch (InterruptedException e) { throw new IllegalStateException(e); }
                return null;
            }));
            assertTrue(repairWritten.await(5, java.util.concurrent.TimeUnit.SECONDS));
            var consumer = pool.submit(() -> {
                consumerStarted.countDown();
                checkpoints.commit("2-0", state(), List.of(new ConsumerCheckpointService.Candle("btcusdt", candidate(60, 1))));
            });
            assertTrue(consumerStarted.await(5, java.util.concurrent.TimeUnit.SECONDS));
            assertFalse(consumer.isDone());
            allowCommit.countDown();
            repair.get(5, java.util.concurrent.TimeUnit.SECONDS);
            consumer.get(5, java.util.concurrent.TimeUnit.SECONDS);
            assertEquals(999L, minuteRows.findBySymbolIdAndBucketTime(symbol.getId(), time(60)).orElseThrow().getVolume());
        } finally {
            allowCommit.countDown();
            pool.shutdownNow();
        }
    }

    private void readAll() {
        redis.opsForStream().createGroup(stream, ReadOffset.from("0-0"), group);
        redis.opsForStream().read(Consumer.from(group, "dead"), StreamReadOptions.empty().count(1000),
                StreamOffset.create(stream, ReadOffset.lastConsumed()));
    }
    private void marker(String key) {
        VerifiedCandle marker = new VerifiedCandle(); marker.setId(key); marker.setCachePublished(true); verified.save(marker);
    }
    private FailedRecord failure(String id, String required) {
        FailedRecord f = new FailedRecord(); f.setId(FailedRecord.key(stream, group, id)); f.setStreamKey(stream);
        f.setConsumerGroup(group); f.setRecordId(id); f.setRequiredCandles(required); return f;
    }
    private ConsumerCheckpointService.State state() {
        return new ConsumerCheckpointService.State(1, new KlineAggregatorService().checkpoint(), Map.of());
    }
    private ClosedKlineSnapshot candidate(long bucket, int volume) {
        return new ClosedKlineSnapshot("M1", new KlineSnapshot(bucket, bucket + 59, BigDecimal.ONE,
                BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE, BigDecimal.valueOf(volume), 1, true));
    }
    private LocalDateTime time(long bucket) { return LocalDateTime.ofEpochSecond(bucket, 0, ZoneOffset.UTC); }
}
