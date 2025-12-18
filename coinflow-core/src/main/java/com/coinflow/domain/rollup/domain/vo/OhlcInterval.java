package com.coinflow.domain.rollup.domain.vo;

import static com.coinflow.common.exception.CoreErrorCode.ROLLUP_INVALID_INTERVAL;

import com.coinflow.common.exception.CoreException;
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

    public int requiredCandleCount(OhlcInterval sourceInterval) {
        if (sourceInterval == null) {
            throw new CoreException(ROLLUP_INVALID_INTERVAL);
        }

        long targetMinutes = this.duration.toMinutes();
        long sourceMinutes = sourceInterval.duration.toMinutes();

        if (sourceMinutes <= 0 || targetMinutes <= 0 || targetMinutes % sourceMinutes != 0) {
            throw new CoreException(ROLLUP_INVALID_INTERVAL);
        }

        return (int) (targetMinutes / sourceMinutes);
    }

    public LocalDateTime nextBucket(LocalDateTime current) {
        return current.plus(duration);
    }

    public boolean isClosed(LocalDateTime bucketStart, LocalDateTime now) {
        return !now.isBefore(bucketStart.plus(duration));
    }
}
