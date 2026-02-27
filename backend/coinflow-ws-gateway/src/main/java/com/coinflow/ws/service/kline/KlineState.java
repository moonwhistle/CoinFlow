package com.coinflow.ws.service.kline;

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
    private BigDecimal volume;
    private int trades;
    private boolean closed;
    private boolean dirty; // has data been updated since last broadcast?

    public KlineState(int durationSeconds) {
        this.durationSeconds = durationSeconds;
        reset(0);
    }

    /**
     * Process a single tick. If the tick belongs to a new candle period,
     * the state is reset first.
     */
    public synchronized void processTick(BigDecimal price, BigDecimal qty, long tickEpochSec) {
        long bucketStart = (tickEpochSec / durationSeconds) * durationSeconds;

        if (this.open == null || bucketStart != this.startTime) {
            // New candle period
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
        this.volume = this.volume.add(qty);
        this.trades++;
        this.dirty = true;
    }

    /**
     * Apply server correction from CandleClosedEvent.
     * Replaces OHLCV with authoritative server values and marks as closed.
     */
    public synchronized void applyClose(long epochSeconds, BigDecimal o, BigDecimal h,
            BigDecimal l, BigDecimal c, BigDecimal v) {
        this.startTime = epochSeconds;
        this.closeTime = epochSeconds + durationSeconds - 1;
        this.open = o;
        this.high = h;
        this.low = l;
        this.close = c;
        this.volume = v;
        this.closed = true;
        this.dirty = true;
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
                volume, trades, closed);
    }

    /**
     * Reset after a closed candle has been broadcast.
     * Called externally after broadcasting a closed kline.
     */
    public synchronized void resetAfterClose() {
        if (this.closed) {
            this.open = null;
            this.closed = false;
            this.dirty = false;
        }
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
        this.volume = BigDecimal.ZERO;
        this.trades = 0;
        this.closed = false;
        this.dirty = false;
    }

    public record KlineSnapshot(
            long startTime,
            long closeTime,
            BigDecimal open,
            BigDecimal high,
            BigDecimal low,
            BigDecimal close,
            BigDecimal volume,
            int trades,
            boolean closed) {
    }
}
