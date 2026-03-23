package com.coinflow.domain.ohlc.constant;

import com.coinflow.util.TimeBucket;
import java.time.Duration;
import java.time.LocalDateTime;

public enum OhlcInterval {

    M1(Duration.ofMinutes(1), Duration.ofSeconds(70)),
    M5(Duration.ofMinutes(5), Duration.ofSeconds(310)),
    M30(Duration.ofMinutes(30), Duration.ofSeconds(1810)),
    ;

    private final Duration duration;
    private final Duration cacheTtl;

    OhlcInterval(Duration duration, Duration cacheTtl) {
        this.duration = duration;
        this.cacheTtl = cacheTtl;
    }

    public Duration duration() {
        return duration;
    }

    public Duration cacheTtl() {
        return cacheTtl;
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
