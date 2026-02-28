package com.coinflow.aggregation.service.kline;

import com.coinflow.aggregation.service.kline.KlineState.KlineSnapshot;
import com.coinflow.domain.ohlc.repository.LiveKlineRepository;
import com.coinflow.event.kline.KlineEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Broadcasts kline snapshots to Redis Pub/Sub and saves them to Redis Key
 * immediately when triggered by the TickProcessService.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class KlineSnapshotBroadcaster {

    public static final String KLINE_BROADCAST_TOPIC = "kline:broadcast";

    private final KlineAggregator aggregator;
    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final LiveKlineRepository liveKlineRepository;

    // symbol:interval -> timestamp (ms)
    private final ConcurrentHashMap<String, Long> lastBroadcastTimes = new ConcurrentHashMap<>();
    private static final long BROADCAST_INTERVAL_MS = 250;

    public void broadcastAndSave(String symbol, String interval, KlineSnapshot snapshot) {
        String cacheKey = symbol.toLowerCase() + ":" + interval;
        long now = System.currentTimeMillis();

        // Throttling Logic for Live Candles
        if (!snapshot.closed()) {
            Long lastTime = lastBroadcastTimes.get(cacheKey);
            if (lastTime != null && (now - lastTime) < BROADCAST_INTERVAL_MS) {
                // Skip broadcast if 250ms hasn't passed
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

            // 1. Save to Redis (SSOT for api-app)
            liveKlineRepository.save(event);

            // 2. Broadcast to Pub/Sub (for ws-gateway)
            String json = objectMapper.writeValueAsString(event);
            redisTemplate.convertAndSend(KLINE_BROADCAST_TOPIC, json);

            // Update last broadcast time
            lastBroadcastTimes.put(cacheKey, now);

            // 3. Reset in-memory state if closed
            if (snapshot.closed()) {
                log.debug("Broadcasted and saved closed kline for {}:{}", symbol, interval);
                aggregator.resetAfterClose(symbol, interval);
            }

        } catch (Exception e) {
            log.error("Failed to broadcast/save kline for {}:{}", symbol, interval, e);
        }
    }
}
