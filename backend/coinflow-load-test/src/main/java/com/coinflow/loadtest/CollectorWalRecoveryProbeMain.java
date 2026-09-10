package com.coinflow.loadtest;

import com.coinflow.common.config.RedisConfig;
import com.coinflow.monitoring.MetricRecorder;
import com.coinflow.publish.config.CollectorDeliveryProperties;
import com.coinflow.publish.stream.RedisStreamTickPublisher;
import com.coinflow.publish.wal.LocalTickWal;
import com.coinflow.publish.wal.WalPipelinedTickPublisher;
import com.coinflow.tick.serialization.TickRawBinaryCodec;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.StreamRecords;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;

/** External-Redis probe used to verify process-crash replay without claiming fsync durability. */
public final class CollectorWalRecoveryProbeMain {

    private CollectorWalRecoveryProbeMain() {}

    public static void main(String[] args) throws Exception {
        if (args.length != 7) {
            throw new IllegalArgumentException(
                    "args: <APPEND_AND_HALT|XADD_AND_HALT|DRAIN> <host> <port> <walDir> <streamKey> <firstTradeId> <count>");
        }
        Action action = Action.valueOf(args[0]);
        String host = args[1];
        int port = Integer.parseInt(args[2]);
        Path walDirectory = Path.of(args[3]);
        String streamKey = args[4];
        long firstTradeId = Long.parseLong(args[5]);
        int count = Integer.parseInt(args[6]);

        if (action == Action.APPEND_AND_HALT) {
            appendAndHalt(walDirectory, firstTradeId, count, null);
            return;
        }

        RedisContext redis = RedisContext.connect(host, port);
        try {
            if (action == Action.XADD_AND_HALT) {
                appendAndHalt(walDirectory, firstTradeId, count,
                        payloads -> redis.publisher(streamKey).publishBatch(payloads));
                return;
            }
            drainAndVerify(redis, walDirectory, streamKey, firstTradeId, count);
        } finally {
            redis.close();
        }
    }

    private static void appendAndHalt(
            Path walDirectory,
            long firstTradeId,
            int count,
            java.util.function.Consumer<List<byte[]>> beforeHalt
    ) throws Exception {
        LocalTickWal wal = new LocalTickWal(walDirectory, 1024 * 1024, 64L * 1024 * 1024);
        List<byte[]> payloads = new java.util.ArrayList<>(count);
        for (int index = 0; index < count; index++) {
            byte[] payload = payload(firstTradeId + index);
            wal.append(payload);
            payloads.add(payload);
        }
        if (beforeHalt != null) {
            beforeHalt.accept(payloads);
        }
        Runtime.getRuntime().halt(0);
    }

    private static void drainAndVerify(
            RedisContext redis,
            Path walDirectory,
            String streamKey,
            long firstTradeId,
            int count
    ) throws Exception {
        CollectorDeliveryProperties properties = new CollectorDeliveryProperties();
        properties.setMode(CollectorDeliveryProperties.Mode.WAL_PIPELINE);
        properties.setWalDirectory(walDirectory);
        properties.setWalSegmentBytes(1024 * 1024);
        properties.setWalMaxBytes(64L * 1024 * 1024);
        properties.setBatchSize(500);
        properties.setFlushInterval(Duration.ofMillis(10));

        WalPipelinedTickPublisher publisher = new WalPipelinedTickPublisher(
                redis.publisher(streamKey), redis.metrics(), properties);
        publisher.afterPropertiesSet();
        try {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
            while ((long) publisher.details().get("walPendingRecords") > 0 && System.nanoTime() < deadline) {
                Thread.sleep(10);
            }
            if ((long) publisher.details().get("walPendingRecords") != 0) {
                throw new IllegalStateException("WAL did not drain: " + publisher.details());
            }
        } finally {
            publisher.destroy();
        }

        List<MapRecord<String, Object, Object>> entries = redis.template().opsForStream()
                .range(streamKey, Range.unbounded());
        Set<Long> unique = new HashSet<>();
        int matchingEntries = 0;
        for (MapRecord<String, Object, Object> entry : entries) {
            Object value = entry.getValue().get(RedisStreamTickPublisher.RAW_PAYLOAD_FIELD);
            if (!(value instanceof byte[] payload)) {
                continue;
            }
            long tradeId = TickRawBinaryCodec.extractTradeId(payload);
            if (tradeId >= firstTradeId && tradeId < firstTradeId + count) {
                matchingEntries++;
                unique.add(tradeId);
            }
        }
        int missing = count - unique.size();
        int duplicates = matchingEntries - unique.size();
        System.out.printf("WAL_RECOVERY_RESULT entries=%d unique=%d missing=%d duplicates=%d checkpoint=%s%n",
                matchingEntries, unique.size(), missing, duplicates, publisher.details().get("checkpointSequence"));
        if (missing != 0) {
            throw new IllegalStateException("Missing WAL records after recovery: " + missing);
        }
    }

    private static byte[] payload(long tradeId) {
        return TickRawBinaryCodec.encode("btcusdt", tradeId,
                new BigDecimal("65000.12345678"), new BigDecimal("0.00123456"),
                System.currentTimeMillis());
    }

    private enum Action { APPEND_AND_HALT, XADD_AND_HALT, DRAIN }

    private record RedisContext(
            LettuceConnectionFactory connectionFactory,
            RedisTemplate<String, byte[]> template,
            SimpleMeterRegistry registry,
            MetricRecorder metrics
    ) implements AutoCloseable {

        static RedisContext connect(String host, int port) {
            LettuceConnectionFactory factory = new LettuceConnectionFactory(
                    new RedisStandaloneConfiguration(host, port));
            factory.afterPropertiesSet();
            factory.start();
            RedisTemplate<String, byte[]> template = new RedisConfig().rawRedisTemplate(factory);
            template.afterPropertiesSet();
            SimpleMeterRegistry registry = new SimpleMeterRegistry();
            return new RedisContext(factory, template, registry, new MetricRecorder(registry));
        }

        RedisStreamTickPublisher publisher(String streamKey) {
            return new RedisStreamTickPublisher(template, metrics, streamKey, 200_000L);
        }

        @Override
        public void close() {
            connectionFactory.destroy();
            registry.close();
        }
    }
}
