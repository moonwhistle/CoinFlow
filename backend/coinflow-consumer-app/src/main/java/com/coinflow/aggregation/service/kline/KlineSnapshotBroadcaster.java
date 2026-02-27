package com.coinflow.aggregation.service.kline;

import com.coinflow.event.kline.KlineEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Broadcasts kline snapshots every 1 second to Redis Pub/Sub so that ws-gateway
 * can relay them to clients.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class KlineSnapshotBroadcaster {

    public static final String KLINE_BROADCAST_TOPIC = "kline:broadcast";

    private final KlineAggregator aggregator;
    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    @Scheduled(fixedRate = 1000)
    public void broadcastSnapshots() {
        for (String symbol : aggregator.getActiveSymbols()) {
            for (var interval : aggregator.getIntervals()) {
                KlineState.KlineSnapshot snapshot = aggregator.takeSnapshot(symbol, interval.name());
                if (snapshot == null)
                    continue;

                broadcast(symbol, interval.name(), snapshot);
            }
        }
    }

    public void broadcast(String symbol, String interval, KlineState.KlineSnapshot snapshot) {
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

            String json = objectMapper.writeValueAsString(event);
            redisTemplate.convertAndSend(KLINE_BROADCAST_TOPIC, json);

            if (snapshot.closed()) {
                log.debug("Broadcasted closed kline to Redis for {}:{}", symbol, interval);
                aggregator.resetAfterClose(symbol, interval);
            }

        } catch (Exception e) {
            log.error("Failed to broadcast kline to Redis for {}:{}", symbol, interval, e);
        }
    }
}
