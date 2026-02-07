package com.coinflow.aggregation.service.flush;

import com.coinflow.event.ohlc.CandleClosedEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class Ohlc1mFlushedEventPublisher {

    private final RedisTemplate<String, String> redisTemplate;
    private final ObjectMapper objectMapper;
    private static final String CHANNEL = "candle:closed";

    public void publish(Long symbolId, String symbolCode, LocalDateTime bucketTime, BigDecimal open, BigDecimal high,
            BigDecimal low,
            BigDecimal close, Long volume) {
        try {
            CandleClosedEvent event = CandleClosedEvent.builder()
                    .symbolId(symbolId)
                    .symbolCode(symbolCode)
                    .bucketTime(bucketTime.toString()) // ISO 8601 default for LocalDateTime.toString()
                    .open(open)
                    .high(high)
                    .low(low)
                    .close(close)
                    .volume(volume)
                    .build();

            String json = objectMapper.writeValueAsString(event);
            redisTemplate.convertAndSend(CHANNEL, json);
            log.debug("Published CandleClosedEvent to Redis channel {}: {}", CHANNEL, json);
        } catch (Exception e) {
            log.error("Failed to publish CandleClosedEvent for symbolId={}", symbolId, e);
        }
    }
}
