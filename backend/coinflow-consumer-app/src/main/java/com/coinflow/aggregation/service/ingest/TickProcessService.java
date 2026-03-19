package com.coinflow.aggregation.service.ingest;

import com.coinflow.aggregation.service.kline.KlineAggregator;
import com.coinflow.aggregation.service.kline.KlineAggregator.AggregationResult;
import com.coinflow.aggregation.service.kline.KlineAggregator.ClosedKlineSnapshot;
import com.coinflow.aggregation.service.kline.KlineSnapshotBroadcaster;
import com.coinflow.aggregation.service.ticker.TickerBroadcaster;
import com.coinflow.aggregation.service.persist.DbPersistService;
import com.coinflow.event.ticker.TickerEvent;
import com.coinflow.tick.event.TickRawEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

@Service
@RequiredArgsConstructor
@Slf4j
public class TickProcessService {

    private final KlineAggregator klineAggregator;
    private final KlineSnapshotBroadcaster klineBroadcaster;
    private final TickerBroadcaster tickerBroadcaster;
    private final DbPersistService dbPersistService;
    private final RedisTemplate<String, String> redisTemplate;

    // symbol -> last processed eventTime (ms) to prevent out-of-order broadcasting
    private final Map<String, Long> lastBroadcastingTimeMap = new ConcurrentHashMap<>();

    public void process(TickRawEvent event, String streamKey, String group, RecordId recordId) {
        log.debug("Processing tick event: {}", event);

        // 1. Broadcast 100% real-time Ticker Event (only if it's newer than last broadcasted)
        long currentEventTime = event.eventTime().toEpochMilli();
        long lastTime = lastBroadcastingTimeMap.getOrDefault(event.symbol(), 0L);

        if (currentEventTime >= lastTime) {
            TickerEvent tickerEvent = new TickerEvent(
                    event.symbol(),
                    event.price(),
                    event.quantity(),
                    currentEventTime);
            tickerBroadcaster.broadcast(tickerEvent);
            lastBroadcastingTimeMap.put(event.symbol(), currentEventTime);
        } else {
            log.info("[Broadcaster] Skipping stale ticker broadcast. symbol={}, eventTime={}, lastTime={}",
                    event.symbol(), currentEventTime, lastTime);
        }

        try {
            // In-Memory Real-time Kline Aggregation (SSOT track)
            AggregationResult result = klineAggregator.processTickAndGetResult(
                    event.symbol(),
                    event.price(),
                    event.quantity(),
                    event.eventTime().toEpochMilli());

            List<CompletableFuture<Void>> futures = new ArrayList<>();

            // 1. Broadcast & Save Late Snapshots
            for (ClosedKlineSnapshot c : result.lateUpdatedSnapshots()) {
                klineBroadcaster.broadcastAndSave(event.symbol(), c.interval(), c.snapshot());
                futures.add(dbPersistService.persistClosedCandleAsync(event.symbol(), c));
            }

            // 2. Broadcast & Save Closed Snapshots
            for (ClosedKlineSnapshot c : result.closedSnapshots()) {
                klineBroadcaster.broadcastAndSave(event.symbol(), c.interval(), c.snapshot());
                futures.add(dbPersistService.persistClosedCandleAsync(event.symbol(), c));
            }

            // 3. Broadcast & Save Live Snapshots
            if (!result.liveSnapshots().isEmpty()) {
                for (ClosedKlineSnapshot c : result.liveSnapshots()) {
                    klineBroadcaster.broadcastAndSave(event.symbol(), c.interval(), c.snapshot());
                }
            }

            // Acknowledge after ALL async persistence tasks complete
            if (futures.isEmpty()) {
                acknowledge(streamKey, group, recordId);
            } else {
                CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                        .thenRun(() -> acknowledge(streamKey, group, recordId))
                        .exceptionally(ex -> {
                            log.error("Async persistence tasks failed for symbol={}. Message will NOT be ACKnowledged.", event.symbol(), ex);
                            return null;
                        });
            }

            log.debug("[Consumer] Successfully processed or enqueued tick event - symbol={}", event.symbol());
        } catch (Exception e) {
            log.error(
                    "[Consumer] Failed to process tick event - symbol={}, price={}",
                    event.symbol(),
                    event.price(),
                    e);

            throw e;
        }
    }

    private void acknowledge(String streamKey, String group, RecordId recordId) {
        redisTemplate.opsForStream().acknowledge(streamKey, group, recordId);
        log.debug("Acknowledged stream message: {}", recordId);
    }
}
