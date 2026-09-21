package com.coinflow.aggregation.service;

import com.coinflow.config.ConsumerApplicationShutdown;
import com.coinflow.domain.aggregation.domain.vo.ClosedKlineSnapshot;
import com.coinflow.domain.aggregation.service.KlineAggregatorService;
import com.coinflow.domain.ohlc.constant.OhlcInterval;
import com.coinflow.domain.recovery.domain.StreamRecordIds;
import com.coinflow.domain.recovery.domain.VerifiedCandle;
import com.coinflow.recovery.service.CandleProjectionPublisher;
import com.coinflow.recovery.service.ConsumerCheckpointService;
import com.coinflow.monitoring.MetricRecorder;
import static com.coinflow.monitoring.constant.MetricConstants.*;
import jakarta.annotation.PreDestroy;
import java.math.BigDecimal;
import java.util.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/** A bounded, ordered micro-batch. Only a committed snapshot permits ACK. */
@Service
@RequiredArgsConstructor
@Slf4j
public class TickProcessService {
    private final KlineAggregatorService aggregator;
    private final ConsumerCheckpointService checkpoints;
    private final CandleProjectionPublisher projections;
    private final BatchAckWorker acknowledgements;
    private final ConsumerApplicationShutdown shutdown;
    private final MetricRecorder metrics;
    @Value("${coinflow.recovery.checkpoint-batch-size:500}") private int batchSize = 500;
    private final Map<String, Long> dedupe = new LinkedHashMap<>();
    private final Map<String, ConsumerCheckpointService.Candle> updates = new LinkedHashMap<>();
    private final List<RecordId> pending = new ArrayList<>();
    private final Map<RecordId, Long> processingStarted = new HashMap<>();
    private String lastRecord = "0-0";
    private String committed = "0-0";
    private boolean ready;
    private boolean baseline;
    private boolean failed;
    private long lastHeartbeat;

    public synchronized String restore() {
        var restored = checkpoints.acquire();
        baseline = restored.state() != null;
        if (restored.state() != null) {
            aggregator.restore(restored.state().aggregate());
            dedupe.putAll(restored.state().dedupe());
        }
        committed = lastRecord = restored.recordId();
        lastHeartbeat = System.currentTimeMillis();
        ready = true;
        return committed;
    }

    public synchronized boolean hasBaseline() { return baseline; }

    /** Persist an empty initial state before the first XREADGROUP can advance the group. */
    public synchronized void establishBaseline() {
        if (!ready || failed) throw new IllegalStateException("Consumer recovery is not ready");
        if (!baseline) {
            checkpoints.commit(lastRecord, new ConsumerCheckpointService.State(1, aggregator.checkpoint(), Map.copyOf(dedupe)), List.of());
            baseline = true;
        }
    }

    public void process(String symbol, BigDecimal price, BigDecimal quantity, long eventTime,
            String stream, String group, RecordId id) {
        process(symbol, null, price, quantity, eventTime, stream, group, id);
    }

    public synchronized void process(String symbol, Long tradeId, BigDecimal price, BigDecimal quantity,
            long eventTime, String stream, String group, RecordId id) {
        if (!ready || failed) throw new IllegalStateException("Consumer recovery is not ready");
        long started = System.nanoTime();
        if (StreamRecordIds.compare(id.getValue(), committed) <= 0) {
            acknowledgements.addAck(id);
            return;
        }
        if (StreamRecordIds.compare(id.getValue(), lastRecord) <= 0) return;
        String key = tradeId == null ? id.getValue() : symbol.toLowerCase(Locale.ROOT) + ':' + tradeId;
        if (!dedupe.containsKey(key)) {
            var before = aggregator.checkpoint();
            try {
                var result = aggregator.processTickAndGetResult(symbol, price, quantity, eventTime);
                Set<String> applied = new HashSet<>();
                result.liveSnapshots().forEach(s -> applied.add(s.interval()));
                result.lateUpdatedSnapshots().forEach(s -> applied.add(s.interval()));
                if (applied.size() != 3) throw new IllegalArgumentException("Tick exceeds late-buffer horizon; batch repair required");
                result.liveSnapshots().forEach(s -> remember(symbol, s));
                result.closedSnapshots().forEach(s -> remember(symbol, s));
                result.lateUpdatedSnapshots().forEach(s -> remember(symbol, s));
            } catch (RuntimeException e) {
                aggregator.restore(before);
                metrics.increment(TICK_PROCESS_STATUS, TAG_STATUS, VALUE_FAILURE);
                throw new InvalidTickException(e);
            }
            dedupe.put(key, System.currentTimeMillis());
        }
        pending.add(id);
        processingStarted.put(id, started);
        lastRecord = id.getValue();
        metrics.recordTimeNanos(TICK_MAIN_THREAD_LATENCY, System.nanoTime() - started, TAG_MODULE, "consumer", TAG_TYPE, "main");
        if (pending.size() >= batchSize) flush();
    }

    /** Include event buckets and buckets that its transition could have closed. */
    public synchronized Set<String> affectedCandles(String symbol, long eventTime) {
        Set<String> keys = new LinkedHashSet<>();
        for (var interval : OhlcInterval.values()) {
            long seconds = interval.duration().toSeconds();
            keys.add(VerifiedCandle.key(symbol, interval.name(), eventTime / 1000 / seconds * seconds));
        }
        aggregator.checkpoint().active().forEach((key, state) -> {
            if (key.startsWith(symbol.toLowerCase(Locale.ROOT) + ':')) keys.add(key + ':' + state.startTime());
        });
        return keys;
    }

    /** A durable failed_record is a replay decision, but is NOT permission to ACK. */
    public synchronized void checkpointSkipped(RecordId id) {
        if (!ready || failed) throw new IllegalStateException("Consumer recovery is not ready");
        if (StreamRecordIds.compare(id.getValue(), lastRecord) > 0) {
            lastRecord = id.getValue();
            flush();
        }
    }

    public synchronized void flush() {
        if (!ready || failed) return;
        try {
            if (pending.isEmpty() && lastRecord.equals(committed)) {
                if (System.currentTimeMillis() - lastHeartbeat >= 10_000) {
                    checkpoints.heartbeat();
                    lastHeartbeat = System.currentTimeMillis();
                }
                return;
            }
            // Source-ID replay is fenced by the checkpoint; this cache additionally covers recent WAL redelivery.
            long expiry = System.currentTimeMillis() - 600_000;
            dedupe.entrySet().removeIf(e -> e.getValue() < expiry);
            while (dedupe.size() > 100_000) dedupe.remove(dedupe.keySet().iterator().next());
            var projection = List.copyOf(updates.values());
            var finalized = projection.stream().filter(c -> c.value().snapshot().closed()).toList();
            long commitStarted = System.nanoTime();
            checkpoints.commit(lastRecord,
                    new ConsumerCheckpointService.State(1, aggregator.checkpoint(), Map.copyOf(dedupe)), finalized);
            metrics.recordTimeNanos("consumer.checkpoint.commit", System.nanoTime() - commitStarted);
            metrics.increment(TICK_PROCESS_STATUS, pending.size(), TAG_STATUS, VALUE_SUCCESS);
            committed = lastRecord;
            baseline = true;
            lastHeartbeat = System.currentTimeMillis();
            var ackIds = List.copyOf(pending);
            pending.clear();
            updates.clear();
            try { projections.publish(projection); }
            catch (RuntimeException e) { log.error("CHECKPOINT_PROJECTION_FAILED checkpoint={}", committed, e); }
            for (RecordId id : ackIds) {
                acknowledgements.addAck(id);
                metrics.recordTimeNanos(TICK_PROCESS_LATENCY, System.nanoTime() - processingStarted.remove(id),
                        TAG_MODULE, "consumer", TAG_TYPE, "e2e");
            }
        } catch (RuntimeException e) {
            failed = true;
            log.error("CHECKPOINT_COMMIT_FAILED: stop consumption; uncommitted IDs remain in PEL", e);
            throw e;
        }
    }

    @Scheduled(fixedDelayString = "${coinflow.recovery.checkpoint-interval-ms:50}")
    public void flushPeriodically() {
        try { flush(); }
        catch (RuntimeException e) { shutdown.request(); }
    }

    @PreDestroy
    public synchronized void close() {
        if (!ready) return;
        try { flush(); } catch (RuntimeException e) { log.error("Uncommitted checkpoint retained for restart", e); }
        try { checkpoints.release(); } catch (RuntimeException e) { log.warn("Checkpoint lease will expire", e); }
        ready = false;
    }

    private void remember(String symbol, ClosedKlineSnapshot candle) {
        updates.put(symbol + ':' + candle.interval() + ':' + candle.snapshot().startTime(),
                new ConsumerCheckpointService.Candle(symbol, candle));
    }

    public static class InvalidTickException extends RuntimeException {
        public InvalidTickException(Throwable cause) { super(cause); }
    }
}
