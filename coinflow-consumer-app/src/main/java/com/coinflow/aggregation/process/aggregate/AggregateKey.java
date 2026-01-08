package com.coinflow.aggregation.process.aggregate;

import java.time.LocalDateTime;

public record AggregateKey(
        Long symbolId,
        LocalDateTime bucket
) {
}
