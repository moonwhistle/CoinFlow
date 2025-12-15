package com.coinflow.config;

import com.coinflow.config.properties.BinanceWebSocketProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties({
        BinanceWebSocketProperties.class,
})
public class BinancePropertiesConfig {
}
