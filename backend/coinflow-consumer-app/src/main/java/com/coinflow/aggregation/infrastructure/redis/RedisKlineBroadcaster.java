package com.coinflow.aggregation.infrastructure.redis;

import com.coinflow.aggregation.service.KlineBroadcaster;
import com.coinflow.event.kline.KlineEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

import com.coinflow.monitoring.MetricRecorder;
import static com.coinflow.monitoring.constant.MetricConstants.*;

/**
 * SRP: Responsibility is ONLY to propagate kline events via Redis Pub/Sub.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RedisKlineBroadcaster implements KlineBroadcaster {

    public static final String KLINE_BROADCAST_TOPIC = "kline:broadcast";
    private static final long BROADCAST_INTERVAL_MS = 250;

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final MetricRecorder metricRecorder;
    private final ConcurrentHashMap<String, Long> lastBroadcastTimes = new ConcurrentHashMap<>();

    @Override
    public void broadcast(KlineEvent event) {
        String cacheKey = event.symbol().toLowerCase() + ":" + event.interval();
        long now = System.currentTimeMillis();

        // Throttling for live (open) candles
        if (!event.closed()) {
            Long lastTime = lastBroadcastTimes.get(cacheKey);
            if (lastTime != null && (now - lastTime) < BROADCAST_INTERVAL_MS) {
                metricRecorder.increment(KLINE_BROADCAST_SKIPPED, TAG_MODULE, "consumer", TAG_TYPE, VALUE_KLINE);
                return;
            }
        }

        try {
            String json = objectMapper.writeValueAsString(event);
            redisTemplate.convertAndSend(KLINE_BROADCAST_TOPIC, Objects.requireNonNull(json));
            lastBroadcastTimes.put(cacheKey, now);
            metricRecorder.increment(BROADCAST_COUNT, TAG_MODULE, "consumer", TAG_TYPE, VALUE_KLINE);
        } catch (Exception e) {
            log.error("Failed to broadcast kline for {}:{}", event.symbol(), event.interval(), e);
        }
    }
}
