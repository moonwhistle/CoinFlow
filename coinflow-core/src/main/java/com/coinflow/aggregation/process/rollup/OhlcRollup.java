package com.coinflow.aggregation.process.rollup;

import java.math.BigDecimal;

public record OhlcRollup(
        BigDecimal open,
        BigDecimal high,
        BigDecimal low,
        BigDecimal close,
        long volume
) {
}
