package com.coinflow.market.controller.response;

import java.math.BigDecimal;

public record MarketStats24hResponse(
        Long symbolId,
        String symbol,
        long windowStartEpochMillis,
        long asOfEpochMillis,
        Long currentCandleStartEpochSeconds,
        BigDecimal currentCandleVolume,
        BigDecimal openPrice,
        BigDecimal currentPrice,
        BigDecimal highPrice,
        BigDecimal lowPrice,
        BigDecimal volume,
        BigDecimal changePercent) {
}
