package com.coinflow.ws.dto;

import java.math.BigDecimal;
import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class TickDto {
    private String symbol;
    private BigDecimal price;
    private Long volume;
    private Long eventTime;
}
