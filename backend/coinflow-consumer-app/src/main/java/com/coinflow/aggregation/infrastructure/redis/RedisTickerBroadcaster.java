package com.coinflow.aggregation.infrastructure.redis;

import com.coinflow.aggregation.service.TickerBroadcaster;
import com.coinflow.event.ticker.TickerEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * SRP: Responsibility is ONLY to propagate ticker events via Redis Pub/Sub.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RedisTickerBroadcaster implements TickerBroadcaster {

    public static final String TICKER_BROADCAST_TOPIC = "ticker:broadcast";

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    @Override
    public void broadcast(TickerEvent event) {
        try {
            String json = objectMapper.writeValueAsString(event);
            redisTemplate.convertAndSend(TICKER_BROADCAST_TOPIC, json);
        } catch (Exception e) {
            log.error("Failed to broadcast ticker for {}: {}", event.symbol(), e.getMessage());
        }
    }
}
