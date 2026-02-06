package com.coinflow.domain.ohlc.event;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class CandleClosedEvent {
    private Long symbolId;
    private String symbolCode;
    private LocalDateTime bucketTime;
    private BigDecimal open;
    private BigDecimal high;
    private BigDecimal low;
    private BigDecimal close;
    private Long volume;
}
