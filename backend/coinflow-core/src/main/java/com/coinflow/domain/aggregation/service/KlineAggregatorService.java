package com.coinflow.domain.aggregation.service;

import com.coinflow.domain.aggregation.domain.KlineState;
import com.coinflow.domain.aggregation.domain.MutableKlineSnapshot;
import com.coinflow.domain.aggregation.domain.vo.ClosedKlineSnapshot;
import com.coinflow.domain.aggregation.domain.vo.KlineSnapshot;
import com.coinflow.domain.aggregation.domain.vo.AggregationResult;
import com.coinflow.domain.ohlc.constant.OhlcInterval;
import com.coinflow.domain.ohlc.policy.VolumeScaler;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Domain service for kline aggregation.
 * Manages KlineState instances per symbol × interval and handles bucket transitions.
 */
@Slf4j
@Component
public class KlineAggregatorService {

    /**
     * Supported intervals for aggregation.
     * Uses OhlcInterval Enum to avoid magic strings.
     */
    private static final List<OhlcInterval> SUPPORTED_INTERVALS = List.of(
            OhlcInterval.M1,
            OhlcInterval.M5,
            OhlcInterval.M30);

    private static final int MAX_RECENT_BUCKETS = 3;
    private static final long BUFFER_TTL_MS = 120_000;

    // key: "btcusdt:M1"
    private final ConcurrentHashMap<String, KlineState> states = new ConcurrentHashMap<>();

    // key: "btcusdt:M1", Map of bucket startTime -> MutableKlineSnapshot
    private final ConcurrentHashMap<String, Map<Long, MutableKlineSnapshot>> recentlyClosed = new ConcurrentHashMap<>();

    /**
     * Processes a single tick for all supported intervals.
     */
    public AggregationResult processTickAndGetResult(String symbol, BigDecimal price, BigDecimal quantity, long epochMs) {
        long epochSec = epochMs / 1000;
        long scaledQty = VolumeScaler.toLong(quantity);

        List<ClosedKlineSnapshot> closedSnapshots = new ArrayList<>();
        List<ClosedKlineSnapshot> liveSnapshots = new ArrayList<>();
        List<ClosedKlineSnapshot> lateUpdatedSnapshots = new ArrayList<>();

        for (OhlcInterval interval : SUPPORTED_INTERVALS) {
            String intervalName = interval.name();
            int durationSeconds = (int) interval.duration().toSeconds();
            String key = buildKey(symbol, intervalName);

            KlineState state = states.computeIfAbsent(key, k -> new KlineState(durationSeconds));
            long bucketStart = (epochSec / durationSeconds) * durationSeconds;

            // 1. Process tick and get closed snapshot (if any)
            KlineSnapshot closed = state.processTick(price, scaledQty, epochSec);
            if (closed != null) {
                closedSnapshots.add(new ClosedKlineSnapshot(intervalName, closed));
                addToRecentlyClosedBuffer(key, closed);
            }

            // 2. Late Tick Handling
            if (closed == null && bucketStart < state.getStartTime()) {
                MutableKlineSnapshot buffered = getFromBuffer(key, bucketStart);
                if (buffered != null) {
                    buffered.applyLateTick(price, scaledQty);
                    lateUpdatedSnapshots.add(new ClosedKlineSnapshot(intervalName, buffered.toSnapshot()));
                } else {
                    log.debug("Late tick too old, discarding. symbol={}, interval={}, bucket={}", 
                            symbol, intervalName, bucketStart);
                }
            } else if (bucketStart >= state.getStartTime()) {
                // 3. Normal Live Tick
                KlineSnapshot live = state.takeSnapshot();
                if (live != null) {
                    liveSnapshots.add(new ClosedKlineSnapshot(intervalName, live));
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
        if (buffer == null) return null;

        MutableKlineSnapshot snapshot = buffer.get(bucketStart);
        if (snapshot != null && snapshot.isExpired(BUFFER_TTL_MS)) {
            return null;
        }
        return snapshot;
    }

    private String buildKey(String symbol, String interval) {
        return symbol.toLowerCase() + ":" + interval;
    }
}
