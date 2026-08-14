package com.coinflow.domain.ohlc.snapshot;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public record OhlcRangeStatistics(
        LocalDateTime firstBucketTime,
        LocalDateTime lastBucketTime,
        BigDecimal openPrice,
        BigDecimal highPrice,
        BigDecimal lowPrice,
        BigDecimal closePrice,
        long volume) {
}
