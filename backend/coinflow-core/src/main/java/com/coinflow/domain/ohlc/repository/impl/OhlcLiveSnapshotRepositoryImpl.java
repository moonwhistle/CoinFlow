package com.coinflow.domain.ohlc.repository.impl;

import com.coinflow.domain.ohlc.constant.OhlcInterval;
import com.coinflow.domain.ohlc.domain.Ohlc1m;
import com.coinflow.domain.ohlc.repository.OhlcLiveSnapshotRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Repository;

import java.time.Duration;

@Slf4j
@Repository
@RequiredArgsConstructor
public class OhlcLiveSnapshotRepositoryImpl implements OhlcLiveSnapshotRepository {

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    private static final String KEY_PREFIX = "ohlc:live:";
    private static final Duration TTL = Duration.ofMinutes(10);

    @Override
    public void save(Long symbolId, OhlcInterval interval, Ohlc1m ohlc1m) {
        String key = generateKey(symbolId, interval);
        try {
            String json = objectMapper.writeValueAsString(ohlc1m);
            // Save to Redis with a TTL so old live candles expire if the consumer stops
            redisTemplate.opsForValue().set(key, json, TTL);
        } catch (JsonProcessingException e) {
            log.error("Failed to serialize Ohlc1m live snapshot for key: {}", key, e);
        }
    }

    private String generateKey(Long symbolId, OhlcInterval interval) {
        return KEY_PREFIX + symbolId + ":" + interval.name();
    }
}
