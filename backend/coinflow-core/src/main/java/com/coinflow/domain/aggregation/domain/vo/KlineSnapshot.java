package com.coinflow.domain.aggregation.domain.vo;

import java.math.BigDecimal;

/**
 * In-memory snapshot of a kline (candle).
 * Immutable DTO for broadcasting and persistence.
 */
public record KlineSnapshot(
        long startTime,
        long closeTime,
        BigDecimal open,
        BigDecimal high,
        BigDecimal low,
        BigDecimal close,
        BigDecimal volume,
        int trades,
        boolean closed
) {
}
