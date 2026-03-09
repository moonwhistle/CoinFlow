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
}
