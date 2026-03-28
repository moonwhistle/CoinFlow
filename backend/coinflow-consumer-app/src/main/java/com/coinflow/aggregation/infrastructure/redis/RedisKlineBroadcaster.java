package com.coinflow.aggregation.infrastructure.redis;

import com.coinflow.aggregation.service.KlineBroadcaster;
import com.coinflow.event.kline.KlineEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * SRP: Responsibility is ONLY to propagate kline events via Redis Pub/Sub.
 * JSON serialization is delegated to the caller (TickProcessService).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RedisKlineBroadcaster implements KlineBroadcaster {

    public static final String KLINE_BROADCAST_TOPIC = "kline:broadcast";
    private static final long BROADCAST_INTERVAL_MS = 250;

    private final StringRedisTemplate redisTemplate;
    private final ConcurrentHashMap<String, Long> lastBroadcastTimes = new ConcurrentHashMap<>();

    @Override
    public void broadcast(KlineEvent event, String preSerializedJson) {
        String cacheKey = event.symbol().toLowerCase() + ":" + event.interval();
        long now = System.currentTimeMillis();

        // Throttling for live (open) candles
        if (!event.closed()) {
            Long lastTime = lastBroadcastTimes.get(cacheKey);
            if (lastTime != null && (now - lastTime) < BROADCAST_INTERVAL_MS) {
                return;
            }
        }

        try {
            redisTemplate.convertAndSend(KLINE_BROADCAST_TOPIC, Objects.requireNonNull(preSerializedJson));
            lastBroadcastTimes.put(cacheKey, now);
        } catch (Exception e) {
            log.error("Failed to broadcast kline for {}:{}", event.symbol(), event.interval(), e);
        }
    }
}
