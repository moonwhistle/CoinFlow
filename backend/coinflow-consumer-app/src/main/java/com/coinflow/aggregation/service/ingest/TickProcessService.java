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

import com.coinflow.monitoring.MetricRecorder;
import static com.coinflow.monitoring.constant.MetricConstants.STREAM_ACK_COUNT;
import static com.coinflow.monitoring.constant.MetricConstants.STREAM_ACK_LATENCY;

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
    private final MetricRecorder metricRecorder;

    // symbol -> last processed eventTime (ms) to prevent out-of-order broadcasting
    private final Map<String, Long> lastBroadcastingTimeMap = new ConcurrentHashMap<>();

    public void process(TickRawEvent event, String streamKey, String group, RecordId recordId) {
        log.debug("Processing tick event: {}", event);

        // 1. 실시간 시세 (Ticker) 브로드캐스팅 방어 및 전파
        broadcastTickerIfNewer(event);

        try {
            // 2. 인메모리 캔들 (Kline) 생성 로직 (SSOT)
            AggregationResult result = aggregateKline(event);

            // 3. 집계된 스냅샷 전파 및 백그라운드 DB 갱신 예약
            List<CompletableFuture<Void>> dbFutures = processAndPersistSnapshots(event.symbol(), result);

            // 4. 모든 비동기 작업 종료 확인 후 Redis ACK 전송
            acknowledgeAfterPersist(dbFutures, streamKey, group, recordId, event.symbol());

            log.debug("[Consumer] Successfully processed or enqueued tick event - symbol={}", event.symbol());
        } catch (Exception e) {
            log.error("[Consumer] Failed to process tick event - symbol={}, price={}", event.symbol(), event.price(), e);
            throw e;
        }
    }

    private void broadcastTickerIfNewer(TickRawEvent event) {
        long currentEventTime = event.eventTime().toEpochMilli();
        long lastTime = lastBroadcastingTimeMap.getOrDefault(event.symbol(), 0L);

        if (currentEventTime >= lastTime) {
            TickerEvent tickerEvent = new TickerEvent(event.symbol(), event.price(), event.quantity(), currentEventTime);
            tickerBroadcaster.broadcast(tickerEvent);
            lastBroadcastingTimeMap.put(event.symbol(), currentEventTime);
        } else {
            log.info("[Broadcaster] Skipping stale ticker broadcast. symbol={}, eventTime={}, lastTime={}",
                    event.symbol(), currentEventTime, lastTime);
        }
    }

    private AggregationResult aggregateKline(TickRawEvent event) {
        return klineAggregator.processTickAndGetResult(
                event.symbol(),
                event.price(),
                event.quantity(),
                event.eventTime().toEpochMilli()
        );
    }

    private List<CompletableFuture<Void>> processAndPersistSnapshots(String symbol, AggregationResult result) {
        List<CompletableFuture<Void>> futures = new ArrayList<>();

        // 완료/지연 캔들 처리 (Live 캐시 + DB 영속화 병행)
        result.lateUpdatedSnapshots().forEach(c -> processClosedSnapshot(symbol, c, futures));
        result.closedSnapshots().forEach(c -> processClosedSnapshot(symbol, c, futures));

        // 진행중 캔들 처리 (Live 캐시 전용)
        result.liveSnapshots().forEach(c -> klineBroadcaster.broadcastAndSave(symbol, c.interval(), c.snapshot()));

        return futures;
    }

    private void processClosedSnapshot(String symbol, ClosedKlineSnapshot snapshot, List<CompletableFuture<Void>> futures) {
        klineBroadcaster.broadcastAndSave(symbol, snapshot.interval(), snapshot.snapshot());
        futures.add(dbPersistService.persistClosedCandleAsync(symbol, snapshot));
    }

    private void acknowledgeAfterPersist(List<CompletableFuture<Void>> futures, String streamKey, String group, RecordId recordId, String symbol) {
        if (futures.isEmpty()) {
            acknowledge(streamKey, group, recordId);
        } else {
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                    .thenRun(() -> acknowledge(streamKey, group, recordId))
                    .exceptionally(ex -> {
                        log.error("Async persistence tasks failed for symbol={}. Message will NOT be ACKnowledged.", symbol, ex);
                        return null;
                    });
        }
    }

    private void acknowledge(String streamKey, String group, RecordId recordId) {
        metricRecorder.recordTime(
                STREAM_ACK_LATENCY,
                () -> {
                    redisTemplate.opsForStream().acknowledge(streamKey, group, recordId);
                    metricRecorder.increment(STREAM_ACK_COUNT);
                }
        );
        log.debug("Acknowledged stream message: {}", recordId);
    }
}
