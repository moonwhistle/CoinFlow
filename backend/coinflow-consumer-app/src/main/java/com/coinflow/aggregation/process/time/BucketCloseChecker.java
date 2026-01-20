package com.coinflow.aggregation.process.time;

import com.coinflow.domain.ohlc.constant.OhlcInterval;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 특정 타임프레임 버킷이 닫혔는지 판단한다.
 * 닫힘 기준: now >= bucketStart + interval.duration
 */
@Component
@RequiredArgsConstructor
public class BucketCloseChecker {

    private final BucketTimeProvider timeProvider;

    public boolean isOpen(OhlcInterval interval, LocalDateTime bucketStart) {
        LocalDateTime now = timeProvider.nowUtc();
        return now.isBefore(bucketStart.plus(interval.duration()));
    }
}
