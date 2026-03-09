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
        if (rawKline == null || rawKline.length < 11) {
            throw new IllegalArgumentException("Invalid Binance API response: kline array length is less than 11");
        }

        return new BinanceKline(
                Long.parseLong(String.valueOf(rawKline[0])),
                new BigDecimal(String.valueOf(rawKline[1])),
                new BigDecimal(String.valueOf(rawKline[2])),
                new BigDecimal(String.valueOf(rawKline[3])),
                new BigDecimal(String.valueOf(rawKline[4])),
                new BigDecimal(String.valueOf(rawKline[5])),
                Long.parseLong(String.valueOf(rawKline[6])),
                new BigDecimal(String.valueOf(rawKline[7])),
                Integer.parseInt(String.valueOf(rawKline[8])),
                new BigDecimal(String.valueOf(rawKline[9])),
                new BigDecimal(String.valueOf(rawKline[10])));
    }
}
