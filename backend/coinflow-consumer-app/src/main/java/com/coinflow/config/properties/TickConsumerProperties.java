package com.coinflow.config.properties;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@ConfigurationProperties(prefix = "redis.stream.tick")
@Validated
public record TickConsumerProperties(
        @NotBlank String streamKey,
        @NotBlank String group,
        @NotBlank String consumerName,
        @Positive long maxLength,
        @DecimalMin("0.0") @DecimalMax("1.0") double lagWarningRatio
) {
}
