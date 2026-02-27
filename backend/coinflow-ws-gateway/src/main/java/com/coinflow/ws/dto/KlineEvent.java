package com.coinflow.ws.dto;

import java.math.BigDecimal;
import lombok.Builder;

/**
 * Kline (candlestick) event DTO sent to WebSocket clients.
 * Matches the Binance kline WebSocket stream format.
 *
 * Sent every ~1 second with current candle state.
 * When closed=true, this is the final value for the candle.
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
