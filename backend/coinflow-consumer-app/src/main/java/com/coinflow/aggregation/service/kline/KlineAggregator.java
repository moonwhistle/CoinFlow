package com.coinflow.aggregation.service.kline;

import com.coinflow.aggregation.service.kline.KlineState.KlineSnapshot;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
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

    public record ClosedKlineSnapshot(String interval, KlineSnapshot snapshot) {
    }

    public record AggregationResult(
            List<ClosedKlineSnapshot> closedSnapshots,
            List<ClosedKlineSnapshot> liveSnapshots) {
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

        for (IntervalDef interval : INTERVALS) {
            String key = buildKey(symbol, interval.name());
            KlineState state = states.computeIfAbsent(key, k -> new KlineState(interval.seconds()));

            // 1. Process tick and get closed snapshot (if any)
            KlineSnapshot closed = state.processTick(price, scaledQty, epochSec);
            if (closed != null) {
                closedSnapshots.add(new ClosedKlineSnapshot(interval.name(), closed));
            }

            // 2. Get current live snapshot (should always exist after processTick)
            KlineSnapshot live = state.takeSnapshot();
            if (live != null) {
                liveSnapshots.add(new ClosedKlineSnapshot(interval.name(), live));
            }
        }
        return new AggregationResult(closedSnapshots, liveSnapshots);
    }

    /**
     * Reset state after a closed candle has been broadcast.
     */
    public void resetAfterClose(String symbol, String interval) {
        String key = buildKey(symbol, interval);
        KlineState state = states.get(key);
        if (state != null) {
            state.resetAfterClose();
        }
    }

    private String buildKey(String symbol, String interval) {
        return symbol.toLowerCase() + ":" + interval;
    }

    public record IntervalDef(String name, int seconds) {
    }
}
