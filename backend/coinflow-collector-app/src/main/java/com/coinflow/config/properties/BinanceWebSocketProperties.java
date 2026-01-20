package com.coinflow.config.properties;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@ConfigurationProperties(prefix = "binance.websocket")
@Validated
public record BinanceWebSocketProperties(
        @NotBlank String baseUrl,
        @NotEmpty List<String> symbols,
        @NotBlank String tradeStreamSuffix
) {
}
