package com.coinflow.aggregation.infrastructure.redis;

import com.coinflow.domain.ohlc.constant.OhlcInterval;
import com.coinflow.domain.ohlc.domain.Ohlc1m;
import com.coinflow.domain.ohlc.repository.OhlcLiveSnapshotRepository;
import com.coinflow.domain.symbol.domain.Symbol;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.LocalDateTime;
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
    public void save(Long symbolId, OhlcInterval interval, Ohlc1m ohlc1m) {
        String key = generateKey(symbolId, interval);
        try {
            String json = objectMapper.writeValueAsString(ohlc1m);
            redisTemplate.opsForValue().set(key, json, TTL);
        } catch (Exception e) {
            log.error("Failed to serialize or save Ohlc1m live snapshot for key: {}", key, e);
        }
    }

    @Override
    public Optional<Ohlc1m> find(Long symbolId, OhlcInterval interval) {
        String key = generateKey(symbolId, interval);
        String json = redisTemplate.opsForValue().get(key);
        if (json == null) {
            return Optional.empty();
        }
        try {
            JsonNode root = objectMapper.readTree(json);

            // Symbol 정보는 id와 code 정도로 충분하지만, 완벽한 객체를 위해선 SymbolService 조회가 필요함.
            // 하지만 RepositoryImpl에서 Service를 참조하는 것은 의존성 역전 원칙상 좋지 않을 수 있으므로,
            // 여기서는 임시 객체로 생성하거나(ID만 세팅), 기존 코드를 봤을 때 Symbol 조회가 필요할 수 있음.
            // 일단 Snapshot에 저장된 데이터를 기반으로 Proxy 객체처럼 Symbol을 세팅해줍니다.
            // Ohlc1m은 API 응답용으로 주로 price/volume/bucketTime을 씁니다.

            JsonNode symbolNode = root.get("symbol");
            Symbol symbol = Symbol.builder()
                    .id(symbolNode.get("id").asLong())
                    .symbol(symbolNode.get("symbol").asText())
                    .build();

            Ohlc1m obj = Ohlc1m.builder()
                    .symbol(symbol)
                    .bucketTime(LocalDateTime.parse(root.get("bucketTime").asText()))
                    .open(new BigDecimal(root.get("openPrice").asText()))
                    .high(new BigDecimal(root.get("highPrice").asText()))
                    .low(new BigDecimal(root.get("lowPrice").asText()))
                    .close(new BigDecimal(root.get("closePrice").asText()))
                    .volume(root.get("volume").asLong())
                    .build();

            return Optional.of(obj);
        } catch (Exception e) {
            log.error("Failed to deserialize Ohlc1m live snapshot for key: {}", key, e);
            return Optional.empty();
        }
    }

    private String generateKey(Long symbolId, OhlcInterval interval) {
        return KEY_PREFIX + symbolId + ":" + interval.name();
    }
}
