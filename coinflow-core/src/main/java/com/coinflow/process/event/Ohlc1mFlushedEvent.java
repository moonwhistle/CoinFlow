package com.coinflow.process.event;

import java.time.Instant;
import java.time.LocalDateTime;

public record Ohlc1mFlushedEvent(
        Long symbolId,
        LocalDateTime bucketStart1m,
        Instant publishedAt
) {
}
