package com.coinflow.aggregation.domain.model;

import com.coinflow.aggregation.domain.model.dto.KlineSnapshot;
import com.coinflow.domain.ohlc.policy.VolumeScaler;
import java.math.BigDecimal;

/**
 * In-memory state of a single kline (candle) for one symbol × one interval.
 * Thread-safe via synchronized methods.
 *
 * Mirrors the Binance kline WebSocket format:
 * - Server aggregates ticks into OHLCV
 * - Broadcasts snapshot every 1 second
 * - Marks candle as closed when interval ends
 */
public class KlineState {

    private final int durationSeconds;

    private long startTime; // epoch seconds (candle start)
    private long closeTime; // epoch seconds (candle end = startTime + duration - 1)
    private BigDecimal open;
    private BigDecimal high;
    private BigDecimal low;
    private BigDecimal close;
    private long volume;
    private int trades;
    private boolean closed;
    private boolean dirty; // has data been updated since last broadcast?

    public KlineState(int durationSeconds) {
        this.durationSeconds = durationSeconds;
        reset(0);
    }

    public synchronized long getStartTime() {
        return this.startTime;
    }

    /**
     * Process a single tick.
     * Returns a snapshot of the *previous* closed candle if this tick caused a
     * bucket transition.
     * Otherwise returns null.
     */
    public synchronized KlineSnapshot processTick(BigDecimal price, long scaledQty, long tickEpochSec) {
        long bucketStart = (tickEpochSec / durationSeconds) * durationSeconds;

        // Ignore late ticks in KlineState (KlineAggregator handles them via
        // MutableKlineSnapshot buffer)
        if (this.open != null && bucketStart < this.startTime) {
            return null;
        }

        KlineSnapshot closedSnapshot = null;

        if (this.open == null || bucketStart > this.startTime) {
            // If there's an existing open candle, close it and capture snapshot
            if (this.open != null) {
                this.closed = true;
                closedSnapshot = new KlineSnapshot(
                        startTime, closeTime,
                        open, high, low, close,
                        VolumeScaler.toBigDecimal(volume), trades, closed);
            }
            // Start new candle period
            reset(bucketStart);
            this.open = price;
            this.high = price;
            this.low = price;
        } else {
            // Update existing candle
            if (price.compareTo(this.high) > 0)
                this.high = price;
            if (price.compareTo(this.low) < 0)
                this.low = price;
        }

        this.close = price;
        this.volume = Math.addExact(this.volume, scaledQty);
        this.trades++;
        this.dirty = true;

        return closedSnapshot;
    }

    /**
     * Take a snapshot for broadcasting. Resets the dirty flag.
     * Returns null if no data or not dirty.
     */
    public synchronized KlineSnapshot takeSnapshot() {
        if (this.open == null || !this.dirty) {
            return null;
        }
        this.dirty = false;
        return new KlineSnapshot(
                startTime, closeTime,
                open, high, low, close,
                VolumeScaler.toBigDecimal(volume), trades, closed);
    }

    public synchronized boolean hasData() {
        return this.open != null;
    }

    private void reset(long bucketStart) {
        this.startTime = bucketStart;
        this.closeTime = bucketStart + durationSeconds - 1;
        this.open = null;
        this.high = null;
        this.low = null;
        this.close = null;
        this.volume = 0L;
        this.trades = 0;
        this.closed = false;
        this.dirty = false;
    }
}
