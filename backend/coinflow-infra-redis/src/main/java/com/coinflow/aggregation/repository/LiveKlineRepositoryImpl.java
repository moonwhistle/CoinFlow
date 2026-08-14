package com.coinflow.aggregation.repository;

import com.coinflow.domain.ohlc.repository.LiveKlineRepository;
import com.coinflow.event.kline.KlineEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Objects;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Repository;

@Slf4j
@Repository
@RequiredArgsConstructor
public class LiveKlineRepositoryImpl implements LiveKlineRepository {

    public static final String KEY_PREFIX = "kline:live:";
    private static final DefaultRedisScript<Long> DELETE_IF_BUCKET_MATCHES_SCRIPT =
            new DefaultRedisScript<>(
                    "local value = redis.call('GET', KEYS[1]); "
                            + "if not value then return 0 end; "
                            + "local event = cjson.decode(value); "
                            + "if tostring(event.startTime) == ARGV[1] then "
                            + "return redis.call('DEL', KEYS[1]); end; return 0;",
                    Long.class
            );

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    @Override
    public void save(KlineEvent klineEvent, String preSerializedJson) {
        String key = buildKey(klineEvent.symbol(), klineEvent.interval());
        try {
            redisTemplate.opsForValue().set(Objects.requireNonNull(key), Objects.requireNonNull(preSerializedJson));
        } catch (Exception e) {
            log.error("Failed to save live kline to Redis: {}", key, e);
        }
    }

    @Override
    public Optional<KlineEvent> findBySymbolAndInterval(String symbol, String interval) {
        String key = buildKey(symbol, interval);
        try {
            String json = redisTemplate.opsForValue().get(Objects.requireNonNull(key));
            if (json == null) {
                return Optional.empty();
            }
            return Optional.of(objectMapper.readValue(json, KlineEvent.class));
        } catch (Exception e) {
            log.warn("Failed to read live kline from Redis: {}", key, e);
            return Optional.empty();
        }
    }

    @Override
    public void delete(String symbol, String interval) {
        String key = buildKey(symbol, interval);
        redisTemplate.delete(key);
    }

    @Override
    public void deleteIfStartTimeMatches(String symbol, String interval, long startTime) {
        String key = buildKey(symbol, interval);
        redisTemplate.execute(
                DELETE_IF_BUCKET_MATCHES_SCRIPT,
                java.util.List.of(key),
                Long.toString(startTime)
        );
    }

    private String buildKey(String symbol, String interval) {
        return KEY_PREFIX + symbol.toLowerCase() + ":" + interval;
    }
}
