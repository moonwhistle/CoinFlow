package com.coinflow.ws.dto;

import java.math.BigDecimal;
import lombok.Builder;

@Builder
public record TickDto(
                String symbol,
                BigDecimal price,
                BigDecimal volume,
                Long eventTime) {
}
