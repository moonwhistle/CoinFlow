package com.coinflow.aggregation.infrastructure.redis;

import com.coinflow.aggregation.service.TickerBroadcaster;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * SRP: Responsibility is ONLY to propagate ticker events via Redis Pub/Sub.
 * JSON serialization is delegated to the caller (TickProcessService).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RedisTickerBroadcaster implements TickerBroadcaster {

    public static final String TICKER_BROADCAST_TOPIC = "ticker:broadcast";

    private final StringRedisTemplate redisTemplate;

    @Override
    public void broadcast(String preSerializedJson) {
        try {
            redisTemplate.convertAndSend(TICKER_BROADCAST_TOPIC, preSerializedJson);
        } catch (Exception e) {
            log.error("Failed to broadcast ticker: {}", e.getMessage());
        }
    }
}
