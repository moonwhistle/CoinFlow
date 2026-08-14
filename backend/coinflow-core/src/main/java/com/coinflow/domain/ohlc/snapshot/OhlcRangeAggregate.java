package com.coinflow.domain.ohlc.snapshot;

import java.math.BigDecimal;

public record OhlcRangeAggregate(
        BigDecimal highPrice,
        BigDecimal lowPrice,
        Long volume) {
}
