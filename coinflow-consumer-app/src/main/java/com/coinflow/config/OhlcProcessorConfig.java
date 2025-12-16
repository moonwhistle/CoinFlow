package com.coinflow.config;

import com.coinflow.processor.DefaultOhlcProcessor;
import com.coinflow.processor.OhlcProcessor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OhlcProcessorConfig {

    @Bean
    public OhlcProcessor ohlcProcessor() {
        return new DefaultOhlcProcessor();
    }
}
