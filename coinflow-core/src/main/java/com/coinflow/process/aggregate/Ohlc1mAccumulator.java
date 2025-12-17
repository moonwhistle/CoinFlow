package com.coinflow.process.aggregate;

import java.math.BigDecimal;
import java.time.Instant;
import lombok.Getter;

@Getter
public class Ohlc1mAccumulator {

    private final BigDecimal open;
    private BigDecimal high;
    private BigDecimal low;
    private BigDecimal close;
    private long volume;

    /** close 결정용 (late tick 보정) */
    private Instant closeTime;

    private Ohlc1mAccumulator(BigDecimal price, long volume, Instant eventTime) {
        this.open = price;
        this.high = price;
        this.low = price;
        this.close = price;
        this.volume = volume;
        this.closeTime = eventTime;
    }

    public static Ohlc1mAccumulator first(BigDecimal price, long volume, Instant eventTime) {
        return new Ohlc1mAccumulator(price, volume, eventTime);
    }

    public void apply(BigDecimal price, long vol, Instant eventTime) {
        if (price.compareTo(high) > 0) {
            high = price;
        }
        if (price.compareTo(low) < 0) {
            low = price;
        }

        // late tick 보정
        if (eventTime.isAfter(closeTime)) {
            closeTime = eventTime;
            close = price;
        }

        // volume overflow
        volume = Math.addExact(volume, vol);
    }
}
