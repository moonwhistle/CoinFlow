package com.coinflow.domain.ohlc.constant;

import java.time.Duration;

public enum OhlcInterval {

    M1(Duration.ofMinutes(1)),
    M5(Duration.ofMinutes(5)),
    M30(Duration.ofMinutes(30)),
    D1(Duration.ofDays(1));

    private final Duration duration;

    OhlcInterval(Duration duration) {
        this.duration = duration;
    }

    public Duration duration() {
        return duration;
    }
}
