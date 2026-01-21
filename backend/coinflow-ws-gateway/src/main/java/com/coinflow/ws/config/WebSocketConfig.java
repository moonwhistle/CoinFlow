package com.coinflow.ws.config;

import com.coinflow.ws.handler.CoinflowWebSocketHandler;
import java.util.Map;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.HandlerMapping;
import org.springframework.web.reactive.handler.SimpleUrlHandlerMapping;
import org.springframework.web.reactive.socket.WebSocketHandler;
import org.springframework.web.reactive.socket.server.support.WebSocketHandlerAdapter;

@Configuration
public class WebSocketConfig {

    private static final String WS_PATH = "/ws/v1/coinflow";

    @Bean
    public HandlerMapping webSocketHandlerMapping(CoinflowWebSocketHandler handler) {
        Map<String, WebSocketHandler> map = Map.of(WS_PATH, handler);
        SimpleUrlHandlerMapping mapping = new SimpleUrlHandlerMapping();
        mapping.setUrlMap(map);
        mapping.setOrder(1); // Higher precedence

        return mapping;
    }

    @Bean
    public WebSocketHandlerAdapter handlerAdapter() {
        return new WebSocketHandlerAdapter();
    }
}
