package com.coinflow.domain.ohlc.constant;

import com.coinflow.util.TimeBucket;
import java.time.Duration;
import java.time.LocalDateTime;

public enum OhlcInterval {

    M1(Duration.ofMinutes(1)),
    M5(Duration.ofMinutes(5)),
    M30(Duration.ofMinutes(30)),
    ;

    private final Duration duration;

    OhlcInterval(Duration duration) {
        this.duration = duration;
    }

    public Duration duration() {
        return duration;
    }

    public LocalDateTime resolveBucketStart(LocalDateTime bucketStart1m) {
        if (this == M1) {
            return bucketStart1m;
        }

        if (this == M5) {
            return TimeBucket.to5m(bucketStart1m);
        }

        if (this == M30) {
            return TimeBucket.to30m(bucketStart1m);
        }

        throw new IllegalStateException("Unsupported OhlcInterval: " + this);
    }
}
