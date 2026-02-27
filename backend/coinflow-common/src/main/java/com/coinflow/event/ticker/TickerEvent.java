package com.coinflow.event.ticker;

import java.math.BigDecimal;

public record TickerEvent(
        String symbol,
        BigDecimal price,
        BigDecimal volume,
        long eventTime) {
}
