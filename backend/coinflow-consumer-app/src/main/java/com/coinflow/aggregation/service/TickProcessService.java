package com.coinflow.aggregation.service;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.coinflow.aggregation.infrastructure.persistence.DbPersistService;
import com.coinflow.domain.aggregation.domain.vo.AggregationResult;
import com.coinflow.domain.aggregation.domain.vo.ClosedKlineSnapshot;
import com.coinflow.domain.aggregation.domain.vo.KlineSnapshot;
import com.coinflow.domain.aggregation.service.KlineAggregatorService;
import com.coinflow.domain.ohlc.constant.OhlcWindowPolicy;
import com.coinflow.domain.ohlc.repository.LiveKlineRepository;
import com.coinflow.domain.ohlc.repository.OhlcWindowRepository;
import com.coinflow.domain.ohlc.snapshot.OhlcCandleSnapshot;
import com.coinflow.event.kline.KlineEvent;
import com.coinflow.monitoring.MetricRecorder;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.stereotype.Service;

import static com.coinflow.monitoring.constant.MetricConstants.*;

/**
 * Orchestrates the tick processing pipeline (SRP).
 * Ensures low-latency propagation and reliable persistence via an async pipeline.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class TickProcessService {

    private final KlineAggregatorService klineAggregatorService;
    private final LiveKlineRepository liveKlineRepository;
    private final OhlcWindowRepository ohlcWindowRepository;
    private final KlineBroadcaster klineBroadcaster;
    private final TickerBroadcaster tickerBroadcaster;
    private final DbPersistService dbPersistService;
    private final MetricRecorder metricRecorder;
    private final BatchAckWorker batchAckWorker;
    private final ObjectMapper objectMapper;

    // 인메모리 중복 방지를 위한 처리 완료 ID 캐시 (LRU 기반 정확한 존재 여부 검증)
    private final Cache<String, Boolean> processedIdCache = Caffeine.newBuilder()
            .maximumSize(100_000)
            .expireAfterWrite(Duration.ofMinutes(1))
            .build();

    private final Map<String, Long> lastBroadcastingTimeMap = new ConcurrentHashMap<>();

    /**
     * Optimized entry point for tick processing (Zero-POJO variant).
     */
    public void process(String symbol, BigDecimal price, BigDecimal quantity, long eventTime, 
                        String streamKey, String group, RecordId recordId) {
        
        // 1. 중복 체크: 이미 처리된 ID라면 비즈니스 로직 스킵 후 ACK만 수행
        if (isDuplicate(symbol, recordId)) {
            batchAckWorker.addAck(recordId);
            return;
        }

        log.trace("Processing raw tick data: symbol={}, price={}, time={}, id={}", 
                symbol, price, eventTime, recordId);

        long startNanos = System.nanoTime();

        try {
            // 1단계: Ticker 전파
            propagateTicker(symbol, price, quantity, eventTime);

            // 2단계: 집계 엔진 호출
            AggregationResult result = klineAggregatorService.processTickAndGetResult(
                    symbol, price, quantity, eventTime
            );

            // 3단계: 결과 조율
            List<CompletableFuture<Void>> dbFutures = coordinateResults(symbol, result);

            metricRecorder.recordTimeNanos(TICK_MAIN_THREAD_LATENCY, System.nanoTime() - startNanos, 
                    TAG_MODULE, "consumer", TAG_TYPE, "main");

            // 4단계: 비동기 완료 후 ACK
            completeAndAcknowledge(dbFutures, streamKey, group, recordId, symbol, startNanos);

        } catch (Exception e) {
            processedIdCache.invalidate(recordId.getValue());
            log.error("[Consumer] Critical failure processing tick - symbol={}", symbol, e);
            recordFailure(symbol);
            throw e;
        }
    }

    private void propagateTicker(String symbol, BigDecimal price, BigDecimal quantity, long eventTime) {
        long lastTime = lastBroadcastingTimeMap.getOrDefault(symbol, 0L);

        if (eventTime >= lastTime) {
            try {
                // Zero-POJO: TickerEvent 객체 생성 및 Jackson 호출 없이 직접 JSON 조립
                String json = "{\"symbol\":\"" + symbol + "\",\"price\":" + price + 
                              ",\"volume\":" + quantity + ",\"eventTime\":" + eventTime + "}";
                tickerBroadcaster.broadcast(json);
                lastBroadcastingTimeMap.put(symbol, eventTime);
            } catch (Exception e) {
                log.error("Failed to propagate ticker for symbol={}", symbol, e);
            }
        }
    }

    private List<CompletableFuture<Void>> coordinateResults(String symbol, AggregationResult result) {
        List<CompletableFuture<Void>> futures = new ArrayList<>();
        result.lateUpdatedSnapshots().forEach(c -> processFinalizedCandidate(symbol, c, futures));
        result.closedSnapshots().forEach(c -> processFinalizedCandidate(symbol, c, futures));
        result.liveSnapshots().forEach(c -> processCandidate(symbol, c));
        return futures;
    }

    private void processCandidate(String symbol, ClosedKlineSnapshot snapshot) {
        KlineEvent event = toEvent(symbol, snapshot.interval(), snapshot.snapshot());
        try {
            String json = objectMapper.writeValueAsString(event);
            liveKlineRepository.save(event, json);
            klineBroadcaster.broadcast(event, json);
        } catch (Exception e) {
            log.error("Failed to serialize kline event for symbol={}, interval={}", symbol, snapshot.interval(), e);
        }
    }

    private void processFinalizedCandidate(String symbol, ClosedKlineSnapshot snapshot,
            List<CompletableFuture<Void>> futures) {
        KlineEvent event = toEvent(symbol, snapshot.interval(), snapshot.snapshot());
        String json;
        try {
            json = objectMapper.writeValueAsString(event);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to serialize finalized kline event", e);
        }

        CompletableFuture<Void> finalizedFuture = dbPersistService
                .persistClosedCandleAsync(symbol, snapshot)
                .thenRun(() -> {
                    OhlcCandleSnapshot candle = toOhlcSnapshot(snapshot.snapshot());
                    ohlcWindowRepository.save(symbol, snapshot.interval(), candle);
                    ohlcWindowRepository.trim(
                            symbol, snapshot.interval(), OhlcWindowPolicy.MAX_SIZE);
                    liveKlineRepository.deleteIfStartTimeMatches(
                            symbol, snapshot.interval(), snapshot.snapshot().startTime());
                    klineBroadcaster.broadcast(event, json);
                });
        futures.add(finalizedFuture);
    }

    private OhlcCandleSnapshot toOhlcSnapshot(KlineSnapshot snapshot) {
        LocalDateTime bucketTime = LocalDateTime.ofEpochSecond(
                snapshot.startTime(), 0, ZoneOffset.UTC);
        return new OhlcCandleSnapshot(
                bucketTime,
                snapshot.startTime(),
                snapshot.open(),
                snapshot.high(),
                snapshot.low(),
                snapshot.close(),
                snapshot.volume()
        );
    }

    private KlineEvent toEvent(String symbol, String interval, KlineSnapshot snapshot) {
        return KlineEvent.builder()
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
    }

    private void completeAndAcknowledge(List<CompletableFuture<Void>> futures,
            String streamKey, String group, RecordId recordId,
            String symbol, long startNanos) {
        if (futures.isEmpty()) {
            finalizeProcess(streamKey, group, recordId, symbol, startNanos);
        } else {
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                    .thenRun(() -> finalizeProcess(streamKey, group, recordId, symbol, startNanos))
                    .exceptionally(ex -> {
                        processedIdCache.invalidate(recordId.getValue());
                        log.error("Async pipeline failed for {}. Message will stick in PENDING.", symbol, ex);
                        recordFailure(symbol);
                        return (Void) null;
                    });
        }
    }

    private void finalizeProcess(String streamKey, String group, RecordId recordId, String symbol, long startNanos) {
        batchAckWorker.addAck(recordId);
        long e2eDurationNanos = System.nanoTime() - startNanos;
        metricRecorder.recordTimeNanos(TICK_PROCESS_LATENCY, e2eDurationNanos, TAG_MODULE, "consumer", TAG_TYPE, "e2e");
        metricRecorder.increment(TICK_PROCESS_STATUS, TAG_STATUS, VALUE_SUCCESS);
        log.trace("Acknowledge stream successfully (E2E Latency: {}ns) - symbol={}", e2eDurationNanos, symbol);
    }

    private void recordFailure(String symbol) {
        metricRecorder.increment(TICK_PROCESS_STATUS, TAG_STATUS, VALUE_FAILURE);
    }

    /**
     * Checks if the incoming record is a duplicate based on the exact RecordId value.
     * Uses a high-performance Caffeine cache to handle out-of-order re-deliveries.
     */
    private boolean isDuplicate(String symbol, RecordId incomingId) {
        String idValue = incomingId.getValue();
        if (processedIdCache.getIfPresent(idValue) != null) {
            log.trace("Duplicate tick detected for {}: id={}. Skipping aggregation.", 
                    symbol, idValue);
            return true;
        }

        processedIdCache.put(idValue, Boolean.TRUE);
        return false;
    }
}
