package com.coinflow.aggregation.infrastructure.redis;

import java.math.RoundingMode;

import com.coinflow.domain.ohlc.domain.Ohlc1m;
import com.coinflow.domain.ohlc.provider.RealTimeOhlcProvider;
import com.coinflow.domain.symbol.domain.Symbol;
import com.coinflow.domain.symbol.service.SymbolService;
import com.coinflow.event.kline.KlineEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.Optional;

@Slf4j
@Component
@RequiredArgsConstructor
public class OhlcChartSyncProvider implements RealTimeOhlcProvider {

    private final SymbolService symbolService;
    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    @Override
    public Optional<Ohlc1m> getRealTimeCandle(Long symbolId, LocalDateTime bucketTime) {
        log.debug("OhlcChartSyncProvider: getRealTimeCandle called for symbolId={}, bucketTime={}", symbolId,
                bucketTime);

        try {
            Symbol symbol = symbolService.findSymbol(symbolId);
            String key = "kline:live:" + symbol.getSymbol().toLowerCase() + ":M1";

            String json = redisTemplate.opsForValue().get(key);
            if (json == null) {
                log.debug("Live kline not found in Redis for key {}", key);
                return Optional.empty();
            }

            KlineEvent event = objectMapper.readValue(json, KlineEvent.class);

            // requested bucketTime vs event bucketTime (startTime)
            LocalDateTime eventBucketTime = LocalDateTime.ofEpochSecond(event.startTime() / 1000, 0,
                    java.time.ZoneOffset.UTC);
            // Ensure timezone consistency. Assuming bucketTime is also UTC.

            if (!eventBucketTime.equals(bucketTime)) {
                log.debug("Bucket times do NOT match. Requested: {}, Found: {}", bucketTime, eventBucketTime);
                return Optional.empty();
            }

            // Convert KlineEvent -> Ohlc1m
            Ohlc1m result = Ohlc1m.builder()
                    .symbol(null) // Symbol is not used in response
                    .bucketTime(eventBucketTime)
                    .open(event.open())
                    .high(event.high())
                    .low(event.low())
                    .close(event.close())
                    .volume(event.volume().setScale(0, RoundingMode.DOWN).longValue())
                    .build();

            return Optional.of(result);

        } catch (Exception e) {
            log.error("Failed to read live kline for symbolId: {}", symbolId, e);
            return Optional.empty();
        }
    }
}
