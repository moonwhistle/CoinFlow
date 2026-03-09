package com.coinflow.aggregation.service.kline;

import com.coinflow.aggregation.service.kline.KlineState.KlineSnapshot;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import com.coinflow.domain.ohlc.policy.VolumeScaler;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Manages KlineState instances per symbol × interval.
 * Aggregates incoming ticks into OHLCV candles in memory.
 *
 * Supports M1 (60s), M5 (300s), M30 (1800s) intervals simultaneously.
 */
@Slf4j
@Component
public class KlineAggregator {

    private static final List<IntervalDef> INTERVALS = List.of(
            new IntervalDef("M1", 60),
            new IntervalDef("M5", 300),
            new IntervalDef("M30", 1800));

    // key: "btcusdt:M1"
    private final ConcurrentHashMap<String, KlineState> states = new ConcurrentHashMap<>();

    // key: "btcusdt:M1", Map of bucket startTime -> MutableKlineSnapshot
    private final ConcurrentHashMap<String, Map<Long, MutableKlineSnapshot>> recentlyClosed = new ConcurrentHashMap<>();

    private static final int MAX_RECENT_BUCKETS = 3;
    private static final long BUFFER_TTL_MS = 120_000;

    public record ClosedKlineSnapshot(String interval, KlineSnapshot snapshot) {
    }

    public record AggregationResult(
            List<ClosedKlineSnapshot> closedSnapshots,
            List<ClosedKlineSnapshot> liveSnapshots,
            List<ClosedKlineSnapshot> lateUpdatedSnapshots) {
    }

    /**
     * Process a tick for all supported intervals.
     * Returns both the updated live snapshots (for immediate broadcast/Redis SET)
     * and any closed snapshots (due to bucket transition).
     */
    public AggregationResult processTickAndGetResult(String symbol, BigDecimal price, BigDecimal quantity,
            long epochMs) {
        long epochSec = epochMs / 1000;
        long scaledQty = VolumeScaler.toLong(quantity);
        List<ClosedKlineSnapshot> closedSnapshots = new ArrayList<>();
        List<ClosedKlineSnapshot> liveSnapshots = new ArrayList<>();
        List<ClosedKlineSnapshot> lateUpdatedSnapshots = new ArrayList<>();

        for (IntervalDef interval : INTERVALS) {
            String key = buildKey(symbol, interval.name());
            KlineState state = states.computeIfAbsent(key, k -> new KlineState(interval.seconds()));

            long bucketStart = (epochSec / interval.seconds()) * interval.seconds();

            // 1. Process tick and get closed snapshot (if any, triggered by bucket
            // transition)
            KlineSnapshot closed = state.processTick(price, scaledQty, epochSec);
            if (closed != null) {
                closedSnapshots.add(new ClosedKlineSnapshot(interval.name(), closed));
                addToRecentlyClosedBuffer(key, closed);
            }

            // 2. Late Tick Handling
            // If the tick didn't trigger a close, has data, and targets an older bucket
            // than the current one
            if (closed == null && bucketStart < state.getStartTime()) {
                MutableKlineSnapshot buffered = getFromBuffer(key, bucketStart);
                if (buffered != null) {
                    buffered.applyLateTick(price, scaledQty);
                    lateUpdatedSnapshots.add(new ClosedKlineSnapshot(interval.name(), buffered.toSnapshot()));
                } else {
                    log.debug("Late tick too old, discarding. symbol={}, interval={}, bucket={}", symbol,
                            interval.name(), bucketStart);
                }
            } else if (bucketStart >= state.getStartTime()) {
                // 3. Normal Live Tick
                KlineSnapshot live = state.takeSnapshot();
                if (live != null) { // live can be null if tick was skipped somehow, though rare
                    liveSnapshots.add(new ClosedKlineSnapshot(interval.name(), live));
                }
            }
        }
        return new AggregationResult(closedSnapshots, liveSnapshots, lateUpdatedSnapshots);
    }

    private void addToRecentlyClosedBuffer(String key, KlineSnapshot closedSnapshot) {
        Map<Long, MutableKlineSnapshot> buffer = recentlyClosed.computeIfAbsent(
                key,
                k -> Collections.synchronizedMap(new LinkedHashMap<>() {
                    @Override
                    protected boolean removeEldestEntry(Map.Entry<Long, MutableKlineSnapshot> eldest) {
                        return size() > MAX_RECENT_BUCKETS;
                    }
                }));
        buffer.put(closedSnapshot.startTime(), new MutableKlineSnapshot(closedSnapshot));
    }

    private MutableKlineSnapshot getFromBuffer(String key, long bucketStart) {
        Map<Long, MutableKlineSnapshot> buffer = recentlyClosed.get(key);
        if (buffer == null)
            return null;

        MutableKlineSnapshot snapshot = buffer.get(bucketStart);
        if (snapshot != null && snapshot.isExpired(BUFFER_TTL_MS)) {
            // It exceeded TTL, naturally let it fail / ignore
            return null;
        }
        return snapshot;
    }

    private String buildKey(String symbol, String interval) {
        return symbol.toLowerCase() + ":" + interval;
    }

    public record IntervalDef(String name, int seconds) {
    }
}
