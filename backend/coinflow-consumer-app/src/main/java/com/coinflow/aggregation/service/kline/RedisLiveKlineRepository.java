package com.coinflow.aggregation.service.kline;

import com.coinflow.event.kline.KlineEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Slf4j
@Repository
@RequiredArgsConstructor
public class RedisLiveKlineRepository {

    public static final String KEY_PREFIX = "kline:live:";

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    /**
     * Set the current live kline state (JSON) in Redis.
     * Overwrites any existing value.
     */
    public void save(KlineEvent klineEvent) {
        String key = buildKey(klineEvent.symbol(), klineEvent.interval());
        try {
            String json = objectMapper.writeValueAsString(klineEvent);
            redisTemplate.opsForValue().set(key, json);
        } catch (Exception e) {
            log.error("Failed to save live kline to Redis: {}", key, e);
        }
    }

    /**
     * Get the current live kline state from Redis.
     * Used mainly by api-app to merge with DB data for initial load.
     */
    public Optional<KlineEvent> findBySymbolAndInterval(String symbol, String interval) {
        String key = buildKey(symbol, interval);
        try {
            String json = redisTemplate.opsForValue().get(key);
            if (json == null) {
                return Optional.empty();
            }
            return Optional.of(objectMapper.readValue(json, KlineEvent.class));
        } catch (Exception e) {
            log.warn("Failed to read live kline from Redis: {}", key, e);
            return Optional.empty();
        }
    }

    /**
     * Delete the live kline state from Redis.
     * Typically called when a candle is fully closed.
     */
    public void delete(String symbol, String interval) {
        String key = buildKey(symbol, interval);
        redisTemplate.delete(key);
    }

    private String buildKey(String symbol, String interval) {
        return KEY_PREFIX + symbol.toLowerCase() + ":" + interval;
    }
}
