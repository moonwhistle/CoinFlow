package com.coinflow.config.properties;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@ConfigurationProperties(prefix = "coinflow.async.db-persist")
@Validated
public record AsyncDbPersistProperties(
        @Min(1) int corePoolSize,
        @Min(1) int maxPoolSize,
        @Min(1) int queueCapacity,
        @Min(0) int keepAliveSeconds,
        @NotBlank String threadNamePrefix,
        
        // Retry settings
        @Min(1) int maxAttempts,
        @Min(0) long retryDelay,
        @Min(1) double retryMultiplier
) {
}
