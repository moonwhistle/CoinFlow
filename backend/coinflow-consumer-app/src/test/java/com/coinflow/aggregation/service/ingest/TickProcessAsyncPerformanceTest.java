package com.coinflow.aggregation.service.ingest;

import com.coinflow.aggregation.service.kline.KlineAggregator;
import com.coinflow.aggregation.service.kline.KlineAggregator.AggregationResult;
import com.coinflow.aggregation.service.kline.KlineAggregator.ClosedKlineSnapshot;
import com.coinflow.aggregation.service.kline.KlineSnapshotBroadcaster;
import com.coinflow.aggregation.service.kline.KlineState.KlineSnapshot;
import com.coinflow.aggregation.service.persist.DbPersistService;
import com.coinflow.aggregation.service.ticker.TickerBroadcaster;
import com.coinflow.domain.ohlc.repository.Ohlc1mRepository;
import com.coinflow.domain.ohlc.service.Ohlc1mService;
import com.coinflow.domain.symbol.domain.Symbol;
import com.coinflow.domain.symbol.domain.vo.MarketType;
import com.coinflow.domain.symbol.repository.SymbolRepository;
import com.coinflow.tick.event.TickRawEvent;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.util.StopWatch;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicLong;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.when;

@Slf4j
@SpringBootTest
@ActiveProfiles("test")
public class TickProcessAsyncPerformanceTest {

    @Autowired
    private TickProcessService tickProcessService;

    @Autowired
    private DbPersistService dbPersistService;

    @Autowired
    private Ohlc1mService ohlc1mService;

    @Autowired
    private SymbolRepository symbolRepository;

    @Autowired
    private Ohlc1mRepository ohlc1mRepository;

    @Autowired
    @Qualifier("dbPersistExecutor")
    private Executor dbPersistExecutor;

    @MockitoBean
    private KlineAggregator klineAggregator;

    @MockitoBean
    private KlineSnapshotBroadcaster klineBroadcaster;

    @MockitoBean
    private TickerBroadcaster tickerBroadcaster;

    private Symbol savedSymbol;

    @BeforeEach
    void setUp() {
        ohlc1mRepository.deleteAllInBatch();
        symbolRepository.deleteAllInBatch();

        savedSymbol = Symbol.builder()
                .symbol("btcusdt")
                .exchange("BINANCE")
                .name("btcusdt")
                .active(true)
                .marketType(MarketType.SPOT)
                .build();
        savedSymbol = symbolRepository.save(savedSymbol);
    }

    @Test
    @DisplayName("100개 종목 동시 마감(300건 저장) 시나리오에서 스레드 풀 움직임 모니터링")
    void monitorAsyncSpikeLoad() throws InterruptedException {
        // [준비] 100개 종목 * 3개 타입 = 300건의 저장 요청 시뮬레이션 데이터
        int symbolCount = 100;
        int typesPerSymbol = 3;
        int totalRequests = symbolCount * typesPerSymbol;

        KlineSnapshot snapshot = new KlineSnapshot(100L, Instant.now().getEpochSecond(),
                BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE, 0, true);
        ClosedKlineSnapshot closedSnapshot = new ClosedKlineSnapshot("M1", snapshot);

        ThreadPoolTaskExecutor executor = (ThreadPoolTaskExecutor) dbPersistExecutor;

        log.info("=== [성능 테스트 시작] 시나리오: 100개 종목 3종 캔들 동시 마감 (총 {}건) ===", totalRequests);
        log.info("현재 설정 - Core: {}, Max: {}, Queue: {}", 
                executor.getCorePoolSize(), executor.getMaxPoolSize(), executor.getQueueCapacity());

        StopWatch submitWatch = new StopWatch();
        submitWatch.start();

        // 300개 요청 방출
        for (int i = 0; i < totalRequests; i++) {
            dbPersistService.persistClosedCandleAsync("btcusdt", closedSnapshot);
            
            // 50개마다 스레드 풀 상태 로깅
            if ((i + 1) % 50 == 0) {
                logThreadPoolStatus(executor, "방출 중 (" + (i + 1) + ")");
            }
        }
        submitWatch.stop();

        log.info(">>> 메인 스레드 요청 방출 완료! 소요 시간: {}ms", submitWatch.getTotalTimeMillis());
        logThreadPoolStatus(executor, "방출 직후");

        // 모든 작업이 완료될 때까지 대기 및 상태 모니터링
        StopWatch drainWatch = new StopWatch();
        drainWatch.start();
        while (executor.getActiveCount() > 0 || executor.getThreadPoolExecutor().getQueue().size() > 0) {
            logThreadPoolStatus(executor, "처리 중...");
            Thread.sleep(100); // 100ms 마다 체크
        }
        drainWatch.stop();

        log.info(">>> 모든 비동기 작업 처리 완료! 총 소요 시간: {}ms", drainWatch.getTotalTimeMillis());
        log.info("==================================================================");
    }

    private void logThreadPoolStatus(ThreadPoolTaskExecutor executor, String phase) {
        log.info("[{}] Active: {}, Queue: {}, PoolSize: {}", 
                phase,
                executor.getActiveCount(),
                executor.getThreadPoolExecutor().getQueue().size(),
                executor.getPoolSize());
    }

    @Test
    @DisplayName("실제 DB 저장 상황에서 비동기 처리가 Redis Consume 속도에 미치는 영향 측정")
    void measureAsyncPerformance() throws InterruptedException {
        // 기존 테스트 로직 유지 (기존 분석용 보존)
        LocalDateTime bucketTime = LocalDateTime.now();
        var callCount = new AtomicLong(0);
        when(klineAggregator.processTickAndGetResult(eq("btcusdt"), any(), any(), anyLong()))
                .thenAnswer(invocation -> {
                    long baseTime = 159L; 
                    long currentCall = callCount.getAndIncrement();
                    KlineSnapshot dynSnapshot = new KlineSnapshot(100L, baseTime + (currentCall * 60), 
                        BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE, 0, true);
                    ClosedKlineSnapshot dynClosed = new ClosedKlineSnapshot("M1", dynSnapshot);
                    return new AggregationResult(List.of(dynClosed), List.of(), List.of());
                });

        TickRawEvent event = new TickRawEvent("btcusdt", BigDecimal.valueOf(50000), BigDecimal.valueOf(1),
                Instant.now(), "s1");

        int tickCount = 1000; // 시간을 줄이기 위해 1000건으로 조정

        log.info("[1/2] 동기 방식 측정 시작");
        StopWatch syncWatch = new StopWatch();
        syncWatch.start();
        for (int i = 0; i < tickCount; i++) {
            ohlc1mService.applyAndSave(savedSymbol, bucketTime.plusMinutes(i), 
                BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE, 100L);
        }
        syncWatch.stop();

        log.info("[2/2] 비동기 방식 측정 시작");
        StopWatch asyncWatch = new StopWatch();
        asyncWatch.start();
        for (int i = 0; i < tickCount; i++) {
            tickProcessService.process(event);
        }
        asyncWatch.stop();

        log.info("동기: {}s, 비동기: {}s", syncWatch.getTotalTimeSeconds(), asyncWatch.getTotalTimeSeconds());
    }
}
