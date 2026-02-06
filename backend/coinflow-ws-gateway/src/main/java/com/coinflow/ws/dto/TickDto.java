package com.coinflow.ws.dto;

import java.math.BigDecimal;
import lombok.Builder;

@Builder
public record TickDto(
        String symbol,
        BigDecimal price,
        Long volume,
        Long eventTime) {
}
