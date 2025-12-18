package com.coinflow.domain.rollup.domain.vo;

import java.time.Duration;
import java.time.LocalDateTime;

public enum OhlcInterval {

    M5(Duration.ofMinutes(5)),
    M30(Duration.ofMinutes(30)),
    H1(Duration.ofHours(1));

    private final Duration duration;

    OhlcInterval(Duration duration) {
        this.duration = duration;
    }

    public Duration duration() {
        return duration;
    }

    public LocalDateTime nextBucket(LocalDateTime current) {
        return current.plus(duration);
    }

    public boolean isClosed(LocalDateTime bucketStart, LocalDateTime now) {
        return !now.isBefore(bucketStart.plus(duration));
    }
}
