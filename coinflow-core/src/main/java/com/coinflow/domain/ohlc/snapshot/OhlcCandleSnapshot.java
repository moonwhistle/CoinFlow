package com.coinflow.domain.ohlc.snapshot;

import com.coinflow.domain.ohlc.domain.Ohlc1m;
import com.coinflow.domain.ohlc.domain.Ohlc30m;
import com.coinflow.domain.ohlc.domain.Ohlc5m;
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

    public static OhlcCandleSnapshot from(Ohlc1m candle) {
        return new OhlcCandleSnapshot(
                candle.getBucketTime(),
                candle.getOpenPrice(),
                candle.getHighPrice(),
                candle.getLowPrice(),
                candle.getClosePrice(),
                candle.getVolume()
        );
    }

    public static OhlcCandleSnapshot from(Ohlc5m candle) {
        return new OhlcCandleSnapshot(
                candle.getBucketTime(),
                candle.getOpenPrice(),
                candle.getHighPrice(),
                candle.getLowPrice(),
                candle.getClosePrice(),
                candle.getVolume()
        );
    }

    public static OhlcCandleSnapshot from(Ohlc30m candle) {
        return new OhlcCandleSnapshot(
                candle.getBucketTime(),
                candle.getOpenPrice(),
                candle.getHighPrice(),
                candle.getLowPrice(),
                candle.getClosePrice(),
                candle.getVolume()
        );
    }
}
