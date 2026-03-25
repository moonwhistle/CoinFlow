package com.coinflow.aggregation.service;

import com.coinflow.domain.aggregation.domain.vo.ClosedKlineSnapshot;
import com.coinflow.domain.aggregation.domain.vo.AggregationResult;
import com.coinflow.domain.aggregation.domain.vo.KlineSnapshot;
import com.coinflow.domain.aggregation.service.KlineAggregatorService;
import com.coinflow.aggregation.infrastructure.persistence.DbPersistService;
import com.coinflow.domain.ohlc.repository.LiveKlineRepository;
import com.coinflow.event.kline.KlineEvent;
import com.coinflow.event.ticker.TickerEvent;
import com.coinflow.tick.event.TickRawEvent;
import com.coinflow.monitoring.MetricRecorder;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.StopWatch;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

import static com.coinflow.monitoring.constant.MetricConstants.*;

/**
 * Orchestrates the tick processing pipeline (SRP).
 * Ensures low-latency propagation and reliable persistence via an async
 * pipeline.
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
    private final RedisTemplate<String, String> redisTemplate;
    private final MetricRecorder metricRecorder;

    // symbol -> last processed eventTime (ms) to prevent out-of-order broadcasting
    private final Map<String, Long> lastBroadcastingTimeMap = new ConcurrentHashMap<>();

    /**
     * Primary entry point for tick processing.
     * Observability: Measures E2E latency from ingestion to acknowledgement.
     */
    public void process(TickRawEvent event, String streamKey, String group, RecordId recordId) {
        log.debug("Processing tick event: {}", event);

        StopWatch sw = new StopWatch();
        sw.start();

        try {
            // 1단계: 실시간 시세(Ticker) 전파 - 최신성 검증 포함
            propagateTicker(event);

            // 2단계: 도메인 집계 엔진 호출 (Buffer 기반 OHLCV 산출)
            AggregationResult result = klineAggregatorService.processTickAndGetResult(
                    event.symbol(),
                    event.price(),
                    event.quantity(),
                    event.eventTime().toEpochMilli());

            // 3단계: 집계 결과에 따른 저장 및 전파 조율 (SRP)
            List<CompletableFuture<Void>> dbFutures = coordinateResults(event.symbol(), result);

            // 메인 스레드 점유 시간 기록 (비동기 작업 완료 대기 전)
            metricRecorder.recordTime(TICK_MAIN_THREAD_LATENCY, sw.getTotalTimeMillis(), TAG_MODULE, "consumer",
                    TAG_TYPE, "main");

            // 4단계: 비동기 작업(DB 저장 등) 완료 후 Redis ACK 및 지표 기록
            completeAndAcknowledge(dbFutures, streamKey, group, recordId, event.symbol(), sw);

        } catch (Exception e) {
            log.error("[Consumer] Critical failure processing tick - symbol={}", event.symbol(), e);
            recordFailure(event.symbol());
            throw e;
        }
    }

    private void propagateTicker(TickRawEvent event) {
        long currentEventTime = event.eventTime().toEpochMilli();
        long lastTime = lastBroadcastingTimeMap.getOrDefault(event.symbol(), 0L);

        if (currentEventTime >= lastTime) {
            TickerEvent tickerEvent = new TickerEvent(event.symbol(), event.price(), event.quantity(),
                    currentEventTime);
            tickerBroadcaster.broadcast(tickerEvent);
            lastBroadcastingTimeMap.put(event.symbol(), currentEventTime);
        }
    }

    private List<CompletableFuture<Void>> coordinateResults(String symbol, AggregationResult result) {
        List<CompletableFuture<Void>> futures = new ArrayList<>();

        // 마감 및 지연 업데이트된 스냅샷 처리 (저장 + 전파 + DB 예약)
        result.lateUpdatedSnapshots().forEach(c -> processFinalizedCandidate(symbol, c, futures));
        result.closedSnapshots().forEach(c -> processFinalizedCandidate(symbol, c, futures));

        // 진행 중인 실시간 스냅샷 처리 (저장 + 전파 전용)
        result.liveSnapshots().forEach(c -> processCandidate(symbol, c));

        return futures;
    }

    private void processCandidate(String symbol, ClosedKlineSnapshot snapshot) {
        // DRY: 매핑 로직 서비스 내부로 집중
        KlineEvent event = toEvent(symbol, snapshot.interval(), snapshot.snapshot());

        // SRP: 저장과 전파 책임을 분리하여 제어
        liveKlineRepository.save(event);
        klineBroadcaster.broadcast(event);
    }

    private void processFinalizedCandidate(String symbol, ClosedKlineSnapshot snapshot,
            List<CompletableFuture<Void>> futures) {
        processCandidate(symbol, snapshot);

        // 지연/마감 데이터는 DB 영속화를 비동기로 병행
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
            String symbol, StopWatch sw) {
        if (futures.isEmpty()) {
            finalizeProcess(streamKey, group, recordId, symbol, sw);
        } else {
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                    .thenRun(() -> finalizeProcess(streamKey, group, recordId, symbol, sw))
                    .exceptionally(ex -> {
                        log.error("Async pipeline failed for {}. Message will stick in PENDING.", symbol, ex);
                        recordFailure(symbol);
                        return (Void) null;
                    });
        }
    }

    private void finalizeProcess(String streamKey, String group, RecordId recordId, String symbol, StopWatch sw) {
        // 1. Redis ACK 수행
        acknowledge(streamKey, group, recordId);

        // 2. 전체 소요 시간 측정 종료 및 기록
        if (sw.isRunning()) {
            sw.stop();
        }

        metricRecorder.recordTime(TICK_PROCESS_LATENCY, sw.getTotalTimeMillis(), TAG_MODULE, "consumer", TAG_TYPE,
                "e2e");
        metricRecorder.increment(TICK_PROCESS_STATUS, TAG_STATUS, VALUE_SUCCESS);

        log.debug("Acknowledge stream successfully (E2E Latency: {}ms) - symbol={}", sw.getTotalTimeMillis(), symbol);
    }

    private void acknowledge(String streamKey, String group, RecordId recordId) {
        metricRecorder.recordTime(
                STREAM_ACK_LATENCY,
                () -> {
                    redisTemplate.opsForStream().acknowledge(streamKey, group, recordId);
                    metricRecorder.increment(STREAM_ACK_COUNT);
                });
    }

    private void recordFailure(String symbol) {
        metricRecorder.increment(TICK_PROCESS_STATUS, TAG_STATUS, VALUE_FAILURE);
    }
}
