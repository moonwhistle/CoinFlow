package com.coinflow.aggregation.service;

import com.coinflow.domain.aggregation.domain.vo.ClosedKlineSnapshot;
import com.coinflow.domain.aggregation.domain.vo.AggregationResult;
import com.coinflow.domain.aggregation.domain.vo.KlineSnapshot;
import com.coinflow.domain.aggregation.service.KlineAggregatorService;
import com.coinflow.aggregation.infrastructure.persistence.DbPersistService;
import com.coinflow.domain.ohlc.repository.LiveKlineRepository;
import com.coinflow.event.kline.KlineEvent;
import com.coinflow.monitoring.MetricRecorder;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

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
    private final KlineBroadcaster klineBroadcaster;
    private final TickerBroadcaster tickerBroadcaster;
    private final DbPersistService dbPersistService;
    private final MetricRecorder metricRecorder;
    private final BatchAckWorker batchAckWorker;
    private final ObjectMapper objectMapper;

    private final Map<String, Long> lastBroadcastingTimeMap = new ConcurrentHashMap<>();

    /**
     * Optimized entry point for tick processing (Zero-POJO variant).
     * Avoids creation of intermediate TickRawEvent objects.
     */
    public void process(String symbol, BigDecimal price, BigDecimal quantity, long eventTime, 
                        String streamKey, String group, RecordId recordId) {
        
        log.trace("Processing raw tick data: symbol={}, price={}, time={}", symbol, price, eventTime);

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
        processCandidate(symbol, snapshot);
        futures.add(dbPersistService.persistClosedCandleAsync(symbol, snapshot));
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
}
