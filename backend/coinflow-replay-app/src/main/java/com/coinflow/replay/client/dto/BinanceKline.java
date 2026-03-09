package com.coinflow.replay.client.dto;

import java.math.BigDecimal;

public record BinanceKline(
        long openTime,
        BigDecimal open,
        BigDecimal high,
        BigDecimal low,
        BigDecimal close,
        BigDecimal volume,
        long closeTime,
        BigDecimal quoteAssetVolume,
        int numberOfTrades,
        BigDecimal takerBuyBaseAssetVolume,
        BigDecimal takerBuyQuoteAssetVolume) {

    public static BinanceKline fromArray(Object[] rawKline) {
        return new BinanceKline(
                ((Number) rawKline[0]).longValue(),
                new BigDecimal(rawKline[1].toString()),
                new BigDecimal(rawKline[2].toString()),
                new BigDecimal(rawKline[3].toString()),
                new BigDecimal(rawKline[4].toString()),
                new BigDecimal(rawKline[5].toString()),
                ((Number) rawKline[6]).longValue(),
                new BigDecimal(rawKline[7].toString()),
                ((Number) rawKline[8]).intValue(),
                new BigDecimal(rawKline[9].toString()),
                new BigDecimal(rawKline[10].toString()));
    }
}
