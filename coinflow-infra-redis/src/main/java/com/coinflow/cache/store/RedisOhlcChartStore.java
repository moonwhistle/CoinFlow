package com.coinflow.cache.store;

import com.coinflow.cache.ohlc.OhlcCacheKey;
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

@Component
@RequiredArgsConstructor
public class RedisOhlcChartStore implements OhlcChartStore {

    private static final Duration TTL = Duration.ofSeconds(2);

    private final RedisTemplate<String, String> redisTemplate;
    private final ObjectMapper objectMapper;

    @Override
    public Optional<List<OhlcCandleSnapshot>> get(
            Long symbolId,
            OhlcInterval interval,
            LocalDateTime endExclusive
    ) {
        String key = OhlcCacheKey.recent(symbolId, interval, endExclusive);
        String data = redisTemplate.opsForValue()
                .get(key);

        if (data == null) {
            return Optional.empty();
        }

        try {
            return Optional.of(deserialize(data));
        } catch (Exception e) {
            redisTemplate.delete(key);

            return Optional.empty();
        }
    }

    @Override
    public void put(
            Long symbolId,
            OhlcInterval interval,
            LocalDateTime endExclusive,
            List<OhlcCandleSnapshot> candles
    ) {
        String key = OhlcCacheKey.recent(symbolId, interval, endExclusive);

        try {
            String value = serialize(candles);
            redisTemplate.opsForValue()
                    .set(key, value, TTL);
        } catch (Exception ignored) {
            // 캐시 실패해도 다시 재조회 함
        }
    }

    private List<OhlcCandleSnapshot> deserialize(String raw) throws JsonProcessingException {
        return objectMapper.readValue(
                raw,
                objectMapper.getTypeFactory()
                        .constructCollectionType(List.class, OhlcCandleSnapshot.class)
        );
    }

    private String serialize(List<OhlcCandleSnapshot> candles) throws JsonProcessingException {
        return objectMapper.writeValueAsString(candles);
    }
}
