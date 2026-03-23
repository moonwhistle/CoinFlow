package com.coinflow.domain.ohlc.snapshot;

import com.coinflow.domain.ohlc.domain.AbstractOhlc;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

import com.coinflow.domain.ohlc.policy.VolumeScaler;

/**
 * Read-only OHLC candle snapshot for chart queries.
 * Used for API responses and cache storage.
 */
public record OhlcCandleSnapshot(
        LocalDateTime bucketTime,
        long epochSeconds,
        BigDecimal openPrice,
        BigDecimal highPrice,
        BigDecimal lowPrice,
        BigDecimal closePrice,
        BigDecimal volume) {

    public static OhlcCandleSnapshot from(AbstractOhlc candle) {
        return new OhlcCandleSnapshot(
                candle.getBucketTime(),
                candle.getBucketTime().toEpochSecond(ZoneOffset.UTC),
                candle.getOpenPrice(),
                candle.getHighPrice(),
                candle.getLowPrice(),
                candle.getClosePrice(),
                VolumeScaler.toBigDecimal(candle.getVolume()));
    }
}
