package com.coinflow.aggregation.infrastructure.adapter.redis;
 
import com.coinflow.aggregation.service.TickerBroadcaster;
import com.coinflow.event.ticker.TickerEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
 
/**
 * Redis implementation for ticker broadcasting.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RedisTickerBroadcaster implements TickerBroadcaster {
 
    private final RedisTemplate<String, String> redisTemplate;
    private final ObjectMapper objectMapper;
 
    @Override
    public void broadcast(TickerEvent event) {
        try {
            String json = objectMapper.writeValueAsString(event);
            redisTemplate.convertAndSend("ticker:broadcast", json);
        } catch (Exception e) {
            log.error("Failed to broadcast ticker event for symbol: {}", event.symbol(), e);
        }
    }
}
