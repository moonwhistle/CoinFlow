package com.coinflow.domain.ohlc.snapshot;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Read-only OHLC candle snapshot for chart queries.
 * Used for API responses and cache storage.
 */
public record OhlcCandleSnapshot(
        LocalDateTime bucketTime,
        BigDecimal openPrice,
        BigDecimal highPrice,
        BigDecimal lowPrice,
        BigDecimal closePrice,
        Long volume
) {
}
