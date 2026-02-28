package com.coinflow.aggregation.service.ticker;

import com.coinflow.event.ticker.TickerEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class TickerBroadcaster {

    private final RedisTemplate<String, String> redisTemplate;
    private final ObjectMapper objectMapper;

    /**
     * Broadcasts the real-time ticker data immediately (0ms delay) to the
     * 'ticker:broadcast' channel.
     */
    public void broadcast(TickerEvent event) {
        try {
            String json = objectMapper.writeValueAsString(event);
            redisTemplate.convertAndSend("ticker:broadcast", json);
        } catch (Exception e) {
            log.error("Failed to broadcast ticker event for symbol: {}", event.symbol(), e);
        }
    }
}
