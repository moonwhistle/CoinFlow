package com.coinflow.event;

import java.math.BigDecimal;
import java.time.Instant;

public record TickRawEvent(
        String symbol,
        BigDecimal price,
        BigDecimal quantity,
        Instant eventTime
) {
}
