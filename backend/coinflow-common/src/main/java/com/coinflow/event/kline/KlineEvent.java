package com.coinflow.event.kline;

import java.math.BigDecimal;
import lombok.Builder;

/**
 * Kline (candlestick) event DTO published by consumer-app to Redis,
 * and broadcasted by ws-gateway to WebSocket clients.
 * Matches the Binance kline WebSocket stream format.
 */
@Builder
public record KlineEvent(
        String symbol,
        String interval,
        long startTime,
        long closeTime,
        BigDecimal open,
        BigDecimal high,
        BigDecimal low,
        BigDecimal close,
        BigDecimal volume,
        int trades,
        boolean closed) {
}
