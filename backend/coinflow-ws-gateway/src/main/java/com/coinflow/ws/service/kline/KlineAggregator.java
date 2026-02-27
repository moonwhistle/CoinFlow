package com.coinflow.ws.service.kline;

import com.coinflow.ws.service.kline.KlineState.KlineSnapshot;
import java.math.BigDecimal;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
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

    public record ClosedKlineSnapshot(String interval, KlineState.KlineSnapshot snapshot) {
    }

    /**
     * Process a tick for all supported intervals.
     * Returns a list of snapshots for any candles that were closed during this tick
     * (due to traversing a bucket boundary).
     */
    public List<ClosedKlineSnapshot> processTickAndGetClosed(String symbol, BigDecimal price, BigDecimal quantity,
            long epochMs) {
        long epochSec = epochMs / 1000;
        List<ClosedKlineSnapshot> closedSnapshots = new java.util.ArrayList<>();

        for (IntervalDef interval : INTERVALS) {
            String key = buildKey(symbol, interval.name());
            KlineState state = states.computeIfAbsent(key, k -> new KlineState(interval.seconds()));
            KlineState.KlineSnapshot closed = state.processTick(price, quantity, epochSec);

            if (closed != null) {
                closedSnapshots.add(new ClosedKlineSnapshot(interval.name(), closed));
            }
        }
        return closedSnapshots;
    }

    /**
     * Apply CandleClosed correction for a specific interval.
     * Marks the candle as closed with authoritative server values.
     */
    public void processClose(String symbol, String interval,
            long epochSeconds, BigDecimal open, BigDecimal high,
            BigDecimal low, BigDecimal close, BigDecimal volume) {
        String key = buildKey(symbol, interval);
        int duration = getDuration(interval);
        KlineState state = states.computeIfAbsent(key, k -> new KlineState(duration));
        state.applyClose(epochSeconds, open, high, low, close, volume);
    }

    /**
     * Get a snapshot for broadcasting. Returns null if no data or not dirty.
     */
    public KlineSnapshot takeSnapshot(String symbol, String interval) {
        String key = buildKey(symbol, interval);
        KlineState state = states.get(key);
        if (state == null)
            return null;
        return state.takeSnapshot();
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

    /**
     * Get all active symbol keys (symbols that have at least one KlineState).
     */
    public Set<String> getActiveSymbols() {
        Set<String> symbols = new HashSet<>();
        for (String key : states.keySet()) {
            symbols.add(key.split(":")[0]);
        }
        return symbols;
    }

    public List<IntervalDef> getIntervals() {
        return INTERVALS;
    }

    private String buildKey(String symbol, String interval) {
        return symbol.toLowerCase() + ":" + interval;
    }

    private int getDuration(String interval) {
        return INTERVALS.stream()
                .filter(i -> i.name().equals(interval))
                .findFirst()
                .map(IntervalDef::seconds)
                .orElse(60);
    }

    public record IntervalDef(String name, int seconds) {
    }
}
