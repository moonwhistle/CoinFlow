package com.coinflow.event.ohlc;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import lombok.Builder;

@Builder
public record CandleClosedEvent(
        Long symbolId,
        String symbolCode,
        LocalDateTime bucketTime,
        BigDecimal open,
        BigDecimal high,
        BigDecimal low,
        BigDecimal close,
        Long volume) {
}
