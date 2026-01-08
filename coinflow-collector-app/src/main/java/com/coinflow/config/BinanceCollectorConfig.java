package com.coinflow.config;

import com.coinflow.client.DataClient;
import com.coinflow.client.binance.BinanceWebSocketClient;
import com.coinflow.client.binance.support.BinanceStreamUrlBuilder;
import com.coinflow.config.properties.BinanceWebSocketProperties;
import com.coinflow.handler.TickMessageHandler;
import com.coinflow.handler.binance.BinanceTradeMessageHandler;
import com.coinflow.tick.publisher.TickPublisher;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(BinanceWebSocketProperties.class)
public class BinanceCollectorConfig {

    @Bean
    public TickMessageHandler tickMessageHandler(
            ObjectMapper objectMapper,
            TickPublisher publisher
    ) {
        return new BinanceTradeMessageHandler(objectMapper, publisher);
    }

    @Bean
    public BinanceStreamUrlBuilder binanceStreamUrlBuilder(
            BinanceWebSocketProperties properties
    ) {
        return new BinanceStreamUrlBuilder(properties);
    }

    @Bean
    public DataClient binanceDataClient(
            BinanceStreamUrlBuilder builder,
            TickMessageHandler handler
    ) {
        return new BinanceWebSocketClient(builder.build(), handler);
    }
}
