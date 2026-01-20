package com.coinflow.config.properties;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@ConfigurationProperties(prefix = "redis.stream.tick")
@Validated
public record TickConsumerProperties(
        @NotBlank String streamKey,
        @NotBlank String group,
        @NotBlank String consumerName
) {
}
