package com.coinflow.chart.cache.store;

import com.coinflow.chart.cache.ohlc.OhlcCacheKey;
import com.coinflow.domain.ohlc.cache.OhlcChartStore;
import com.coinflow.domain.ohlc.constant.OhlcInterval;
import com.coinflow.domain.ohlc.snapshot.OhlcCandleSnapshot;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;

@RequiredArgsConstructor
public class RedisOhlcChartStore implements OhlcChartStore {

    /** 조회 성능에 따라 interval 별로 TTL 변경 고려*/
    private static final Duration TTL = Duration.ofSeconds(60);

    private final RedisTemplate<String, String> redisTemplate;
    private final ObjectMapper objectMapper;

    @Override
    public Optional<List<OhlcCandleSnapshot>> get(
            Long symbolId,
            OhlcInterval interval,
            int candles,
            LocalDateTime endExclusive
    ) {
        String key = OhlcCacheKey.chartKey(symbolId, interval, candles, endExclusive);
        String raw = redisTemplate.opsForValue().get(key);

        if (raw == null) {
            return Optional.empty();
        }

        try {
            return Optional.of(deserialize(raw));
        } catch (Exception e) {
            redisTemplate.delete(key); // 캐시 오염 제거
            return Optional.empty();
        }
    }

    @Override
    public void put(
            Long symbolId,
            OhlcInterval interval,
            int candles,
            LocalDateTime endExclusive,
            List<OhlcCandleSnapshot> snapshots
    ) {
        String key = OhlcCacheKey.chartKey(symbolId, interval, candles, endExclusive);

        try {
            redisTemplate.opsForValue()
                    .set(key, serialize(snapshots), TTL);
        } catch (Exception ignored) {
            // 캐시 실패는 무시 (read path 보호)
        }
    }

    private List<OhlcCandleSnapshot> deserialize(String raw) throws JsonProcessingException {
        return objectMapper.readValue(
                raw,
                objectMapper.getTypeFactory()
                        .constructCollectionType(List.class, OhlcCandleSnapshot.class)
        );
    }

    private String serialize(List<OhlcCandleSnapshot> snapshots) throws JsonProcessingException {
        return objectMapper.writeValueAsString(snapshots);
    }
}
