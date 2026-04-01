package com.coinflow.chart.repository;

import com.coinflow.domain.ohlc.snapshot.OhlcCandleSnapshot;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Repository;

import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Redis Sorted Set (ZSET) implementation of OhlcWindowRepository.
 * Stores candle snapshots indexed by epoch seconds for O(log N) retrieval and updates.
 */
@Slf4j
@Repository
@RequiredArgsConstructor
public class RedisOhlcWindowRepositoryImpl implements RedisOhlcWindowRepository {

    private final RedisTemplate<String, String> redisTemplate;
    private final ObjectMapper objectMapper;

    private static final String KEY_PREFIX = "klines:window:";

    @SuppressWarnings("ConstantConditions")
    @Override
    public void save(String symbol, String interval, OhlcCandleSnapshot snapshot) {
        String key = buildKey(symbol, interval);
        String json = serialize(snapshot);
        if (json != null) {
            ZSetOperations<String, String> zSetOps = redisTemplate.opsForZSet();
            zSetOps.removeRangeByScore(key, (double) snapshot.epochSeconds(), (double) snapshot.epochSeconds());
            zSetOps.add(key, json, (double) snapshot.epochSeconds());
            log.trace("[REDIS-WINDOW] Saved candle for {} {}: {}", symbol, interval, snapshot.bucketTime());
        }
    }

    @Override
    public void saveAll(String symbol, String interval, List<OhlcCandleSnapshot> snapshots) {
        if (snapshots == null || snapshots.isEmpty()) return;
        
        String key = buildKey(symbol, interval);
        ZSetOperations<String, String> zSetOps = redisTemplate.opsForZSet();

        // Batch remove existing scores to prevent duplicates
        long minScore = snapshots.get(0).epochSeconds();
        long maxScore = snapshots.get(snapshots.size() - 1).epochSeconds();
        zSetOps.removeRangeByScore(key, Math.min(minScore, maxScore), Math.max(minScore, maxScore));

        snapshots.forEach(s -> {
            String json = serialize(s);
            if (json != null) {
                zSetOps.add(key, json, (double) s.epochSeconds());
            }
        });
        log.debug("[REDIS-WINDOW] Batch saved {} candles for {} {}", snapshots.size(), symbol, interval);
    }

    @SuppressWarnings("ConstantConditions")
    @Override
    public List<OhlcCandleSnapshot> findRange(String symbol, String interval, long to, int limit) {
        String key = buildKey(symbol, interval);
        
        // ZREVRANGEBYSCORE: Get 'limit' items from the 'to' timestamp (exclusive) downwards.
        Set<String> jsonSet = redisTemplate.opsForZSet()
                .reverseRangeByScore(key, -1, (double) to - 1, 0, limit);

        if (jsonSet == null || jsonSet.isEmpty()) {
            return Collections.emptyList();
        }

        // Deserialize and reverse back to maintain ascending time order for the chart
        List<OhlcCandleSnapshot> results = jsonSet.stream()
                .map(this::deserialize)
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
        
        Collections.reverse(results);
        return results;
    }

    @Override
    public void trim(String symbol, String interval, int limit) {
        String key = buildKey(symbol, interval);
        // Maintain only the last N items (e.g., 1000)
        Long size = redisTemplate.opsForZSet().size(key);
        if (size != null && size > limit) {
            redisTemplate.opsForZSet().removeRange(key, 0, size - limit - 1);
            log.trace("[REDIS-WINDOW] Trimmed window for {} {}: kept last {}", symbol, interval, limit);
        }
    }

    private String buildKey(String symbol, String interval) {
        return KEY_PREFIX + symbol + ":" + interval;
    }

    private String serialize(OhlcCandleSnapshot snapshot) {
        try {
            return objectMapper.writeValueAsString(snapshot);
        } catch (JsonProcessingException e) {
            log.error("Failed to serialize OhlcCandleSnapshot: {}", snapshot, e);
            return null;
        }
    }

    private OhlcCandleSnapshot deserialize(String json) {
        try {
            return objectMapper.readValue(json, OhlcCandleSnapshot.class);
        } catch (JsonProcessingException e) {
            log.error("Failed to deserialize OhlcCandleSnapshot JSON: {}", json, e);
            return null;
        }
    }
}
