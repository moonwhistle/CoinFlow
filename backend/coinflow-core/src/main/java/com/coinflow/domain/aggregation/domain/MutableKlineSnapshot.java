package com.coinflow.domain.aggregation.domain;

import com.coinflow.domain.aggregation.domain.vo.KlineSnapshot;
import com.coinflow.domain.ohlc.policy.VolumeScaler;
import java.math.BigDecimal;

/**
 * Mutable representation of a recently closed candle.
 * Allows applying late ticks to recalculate OHLCV before final drop.
 */
public class MutableKlineSnapshot {
    private final long startTime;
    private final long closeTime;
    private final BigDecimal open; // Open price is invariant
    private BigDecimal high;
    private BigDecimal low;
    private BigDecimal close; // Needs to be updated if the late tick is the true final tick
    private long volume;
    private int trades;
    private final boolean closed;

    // For TTL eviction logic
    private final long createdAtMs;

    public MutableKlineSnapshot(KlineSnapshot snapshot) {
        this.startTime = snapshot.startTime();
        this.closeTime = snapshot.closeTime();
        this.open = snapshot.open();
        this.high = snapshot.high();
        this.low = snapshot.low();
        this.close = snapshot.close();
        this.volume = VolumeScaler.toLong(snapshot.volume());
        this.trades = snapshot.trades();
        this.closed = snapshot.closed();
        this.createdAtMs = System.currentTimeMillis();
    }

    /**
     * Applies a late tick to the closed candle.
     */
    public synchronized void applyLateTick(BigDecimal price, long scaledQty) {
        if (price.compareTo(this.high) > 0) {
            this.high = price;
        }
        if (price.compareTo(this.low) < 0) {
            this.low = price;
        }

        // In the context of a simple late tick (which usually arrives after the close),
        // we update the close price to reflect this last known price for the bucket.
        // It provides a "truer" close than what the early drop had.
        this.close = price;

        this.volume = Math.addExact(this.volume, scaledQty);
        this.trades++;
    }

    public synchronized KlineSnapshot toSnapshot() {
        return new KlineSnapshot(
                startTime,
                closeTime,
                open,
                high,
                low,
                close,
                VolumeScaler.toBigDecimal(volume),
                trades,
                closed);
    }

    public boolean isExpired(long ttlMs) {
        return System.currentTimeMillis() - this.createdAtMs > ttlMs;
    }
}
