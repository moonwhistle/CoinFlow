package com.coinflow.process.event;

import java.time.Instant;
import java.time.LocalDateTime;

public record Ohlc1mFlushedEvent(
        Long symbolId,
        LocalDateTime bucketStart1m,
        Instant publishedAt
) {

    public static Ohlc1mFlushedEvent of(Long symbolId, LocalDateTime bucketStart1m) {
        return new Ohlc1mFlushedEvent(symbolId, bucketStart1m, Instant.now());
    }
}
