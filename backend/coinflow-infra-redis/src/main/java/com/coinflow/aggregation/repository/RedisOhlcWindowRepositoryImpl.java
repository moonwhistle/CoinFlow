package com.coinflow.aggregation.repository;

import com.coinflow.domain.ohlc.repository.OhlcWindowRepository;
import com.coinflow.domain.ohlc.snapshot.OhlcCandleSnapshot;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Repository;

@Slf4j
@Repository
@RequiredArgsConstructor
public class RedisOhlcWindowRepositoryImpl implements OhlcWindowRepository {

    private static final String KEY_PREFIX = "klines:window:";
    private static final DefaultRedisScript<Long> UPSERT_SCRIPT = new DefaultRedisScript<>(
            "redis.call('ZREMRANGEBYSCORE', KEYS[1], ARGV[1], ARGV[1]); "
                    + "return redis.call('ZADD', KEYS[1], ARGV[1], ARGV[2]);",
            Long.class
    );
    private static final DefaultRedisScript<Long> REPLACE_RANGE_SCRIPT = new DefaultRedisScript<>(
            "redis.call('ZREMRANGEBYSCORE', KEYS[1], ARGV[1], ARGV[2]); "
                    + "local added = 0; "
                    + "for i = 3, #ARGV, 2 do "
                    + "added = added + redis.call('ZADD', KEYS[1], ARGV[i], ARGV[i + 1]); end; "
                    + "return added;",
            Long.class
    );

    private final RedisTemplate<String, String> redisTemplate;
    private final ObjectMapper objectMapper;

    @Override
    public void save(String symbol, String interval, OhlcCandleSnapshot snapshot) {
        String key = buildKey(symbol, interval);
        String score = Long.toString(snapshot.epochSeconds());
        String json = serialize(snapshot);

        redisTemplate.execute(UPSERT_SCRIPT, List.of(key), score, json);
        log.trace("[REDIS-WINDOW] Upserted candle for {} {}: {}",
                symbol, interval, snapshot.bucketTime());
    }

    @Override
    public void saveAll(String symbol, String interval, List<OhlcCandleSnapshot> snapshots) {
        if (snapshots == null || snapshots.isEmpty()) {
            return;
        }

        String key = buildKey(symbol, interval);
        long minScore = snapshots.stream()
                .mapToLong(OhlcCandleSnapshot::epochSeconds)
                .min()
                .orElseThrow();
        long maxScore = snapshots.stream()
                .mapToLong(OhlcCandleSnapshot::epochSeconds)
                .max()
                .orElseThrow();

        List<String> args = new ArrayList<>(2 + snapshots.size() * 2);
        args.add(Long.toString(minScore));
        args.add(Long.toString(maxScore));
        for (OhlcCandleSnapshot snapshot : snapshots) {
            args.add(Long.toString(snapshot.epochSeconds()));
            args.add(serialize(snapshot));
        }
        redisTemplate.execute(REPLACE_RANGE_SCRIPT, List.of(key), args.toArray());

        log.debug("[REDIS-WINDOW] Batch saved {} candles for {} {}",
                snapshots.size(), symbol, interval);
    }

    @Override
    public List<OhlcCandleSnapshot> findRange(String symbol, String interval, long to, int limit) {
        Set<String> jsonSet = redisTemplate.opsForZSet()
                .reverseRangeByScore(buildKey(symbol, interval), -1, (double) to - 1, 0, limit);

        if (jsonSet == null || jsonSet.isEmpty()) {
            return Collections.emptyList();
        }

        List<OhlcCandleSnapshot> results = jsonSet.stream()
                .map(this::deserialize)
                .filter(Objects::nonNull)
                .toList();
        List<OhlcCandleSnapshot> ascending = new ArrayList<>(results);
        Collections.reverse(ascending);
        return ascending;
    }

    @Override
    public void trim(String symbol, String interval, int limit) {
        String key = buildKey(symbol, interval);
        Long size = redisTemplate.opsForZSet().size(key);
        if (size != null && size > limit) {
            redisTemplate.opsForZSet().removeRange(key, 0, size - limit - 1);
        }
    }

    private String buildKey(String symbol, String interval) {
        return KEY_PREFIX + symbol.toLowerCase() + ":" + interval;
    }

    private String serialize(OhlcCandleSnapshot snapshot) {
        try {
            return objectMapper.writeValueAsString(snapshot);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize OHLC window snapshot", e);
        }
    }

    private OhlcCandleSnapshot deserialize(String json) {
        try {
            return objectMapper.readValue(json, OhlcCandleSnapshot.class);
        } catch (JsonProcessingException e) {
            log.error("Failed to deserialize OHLC window snapshot: {}", json, e);
            return null;
        }
    }
}
