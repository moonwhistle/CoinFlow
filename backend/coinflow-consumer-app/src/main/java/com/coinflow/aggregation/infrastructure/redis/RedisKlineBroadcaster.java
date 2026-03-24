package com.coinflow.aggregation.infrastructure.redis;
 
import com.coinflow.aggregation.service.KlineBroadcaster;
import com.coinflow.event.kline.KlineEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
 
import java.util.concurrent.ConcurrentHashMap;
 
/**
 * SRP: ONLY handles Redis Pub/Sub broadcasting.
 * Decoupled from repository (SSOT Cache).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RedisKlineBroadcaster implements KlineBroadcaster {
 
    public static final String KLINE_BROADCAST_TOPIC = "kline:broadcast";
    private static final long BROADCAST_INTERVAL_MS = 250;
 
    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final ConcurrentHashMap<String, Long> lastBroadcastTimes = new ConcurrentHashMap<>();
 
    @Override
    public void broadcast(KlineEvent event) {
        String cacheKey = event.symbol().toLowerCase() + ":" + event.interval();
        long now = System.currentTimeMillis();
 
        // Throttling for live candles
        if (!event.closed()) {
            Long lastTime = lastBroadcastTimes.get(cacheKey);
            if (lastTime != null && (now - lastTime) < BROADCAST_INTERVAL_MS) {
                return;
            }
        }
 
        try {
            String json = objectMapper.writeValueAsString(event);
            redisTemplate.convertAndSend(KLINE_BROADCAST_TOPIC, json);
            lastBroadcastTimes.put(cacheKey, now);
            
            if (event.closed()) {
                log.debug("Broadcasted closed/late kline via Redis: {}:{}", event.symbol(), event.interval());
            }
        } catch (Exception e) {
            log.error("Failed to broadcast kline for {}:{}", event.symbol(), event.interval(), e);
        }
    }
}
