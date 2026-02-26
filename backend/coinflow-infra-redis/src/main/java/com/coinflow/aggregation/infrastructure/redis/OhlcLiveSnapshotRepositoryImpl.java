package com.coinflow.aggregation.infrastructure.redis;

import com.coinflow.domain.ohlc.constant.OhlcInterval;
import com.coinflow.domain.ohlc.repository.OhlcLiveSnapshotRepository;
import com.coinflow.domain.ohlc.snapshot.LiveCandleSnapshot;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Repository;

import java.time.Duration;
import java.util.Optional;

@Slf4j
@Repository
@RequiredArgsConstructor
public class OhlcLiveSnapshotRepositoryImpl implements OhlcLiveSnapshotRepository {

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    private static final String KEY_PREFIX = "ohlc:live:";
    private static final Duration TTL = Duration.ofMinutes(10);

    @Override
    public void save(Long symbolId, OhlcInterval interval, LiveCandleSnapshot snapshot) {
        String key = generateKey(symbolId, interval);
        try {
            String json = objectMapper.writeValueAsString(snapshot);
            redisTemplate.opsForValue().set(key, json, TTL);
        } catch (Exception e) {
            log.error("Failed to serialize or save LiveCandleSnapshot for key: {}", key, e);
        }
    }

    @Override
    public Optional<LiveCandleSnapshot> find(Long symbolId, OhlcInterval interval) {
        String key = generateKey(symbolId, interval);
        String json = redisTemplate.opsForValue().get(key);
        if (json == null) {
            return Optional.empty();
        }
        try {
            LiveCandleSnapshot snapshot = objectMapper.readValue(json, LiveCandleSnapshot.class);
            return Optional.of(snapshot);
        } catch (Exception e) {
            log.error("Failed to deserialize LiveCandleSnapshot for key: {}", key, e);
            return Optional.empty();
        }
    }

    private String generateKey(Long symbolId, OhlcInterval interval) {
        return KEY_PREFIX + symbolId + ":" + interval.name();
    }
}
