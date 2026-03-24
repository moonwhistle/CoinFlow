package com.coinflow.aggregation.infrastructure.adapter.redis;
 
import com.coinflow.aggregation.service.KlineBroadcaster;
import com.coinflow.domain.aggregation.domain.vo.KlineSnapshot;
import com.coinflow.domain.ohlc.repository.LiveKlineRepository;
import com.coinflow.event.kline.KlineEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import java.util.concurrent.ConcurrentHashMap;
 
/**
 * Redis implementation for kline broadcasting.
 * Saves to Redis SSOT and broadcasts to Pub/Sub.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RedisKlineSnapshotBroadcaster implements KlineBroadcaster {
 
    public static final String KLINE_BROADCAST_TOPIC = "kline:broadcast";
 
    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final LiveKlineRepository liveKlineRepository;
 
    private final ConcurrentHashMap<String, Long> lastBroadcastTimes = new ConcurrentHashMap<>();
    private static final long BROADCAST_INTERVAL_MS = 250;
 
    @Override
    public void broadcastAndSave(String symbol, String interval, KlineSnapshot snapshot) {
        String cacheKey = symbol.toLowerCase() + ":" + interval;
        long now = System.currentTimeMillis();
 
        // Throttling for live candles
        if (!snapshot.closed()) {
            Long lastTime = lastBroadcastTimes.get(cacheKey);
            if (lastTime != null && (now - lastTime) < BROADCAST_INTERVAL_MS) {
                return;
            }
        }
 
        try {
            KlineEvent event = KlineEvent.builder()
                    .symbol(symbol)
                    .interval(interval)
                    .startTime(snapshot.startTime())
                    .closeTime(snapshot.closeTime())
                    .open(snapshot.open())
                    .high(snapshot.high())
                    .low(snapshot.low())
                    .close(snapshot.close())
                    .volume(snapshot.volume())
                    .trades(snapshot.trades())
                    .closed(snapshot.closed())
                    .build();
 
            // 1. Redis SSOT
            liveKlineRepository.save(event);
 
            // 2. Pub/Sub
            String json = objectMapper.writeValueAsString(event);
            redisTemplate.convertAndSend(KLINE_BROADCAST_TOPIC, json);
 
            lastBroadcastTimes.put(cacheKey, now);
 
            if (snapshot.closed()) {
                log.debug("Broadcasted closed/late kline via Redis: {}:{}", symbol, interval);
            }
        } catch (Exception e) {
            log.error("Failed to broadcast kline for {}:{}", symbol, interval, e);
        }
    }
}
