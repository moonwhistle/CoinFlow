package com.coinflow.event.ohlc;

import java.math.BigDecimal;
import lombok.Builder;

@Builder
public record CandleClosedEvent(
                Long symbolId,
                String symbolCode,
                String interval,
                long epochSeconds,
                BigDecimal open,
                BigDecimal high,
                BigDecimal low,
                BigDecimal close,
                BigDecimal volume) {
}
