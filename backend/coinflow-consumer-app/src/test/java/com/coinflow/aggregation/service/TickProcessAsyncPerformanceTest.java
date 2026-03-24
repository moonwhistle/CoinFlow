package com.coinflow.aggregation.service;
 
import com.coinflow.domain.aggregation.domain.vo.ClosedKlineSnapshot;
import com.coinflow.domain.aggregation.domain.vo.KlineSnapshot;
import com.coinflow.domain.aggregation.service.KlineAggregatorService;
import com.coinflow.aggregation.infrastructure.persistence.DbPersistService;
import com.coinflow.domain.ohlc.repository.Ohlc1mRepository;
import com.coinflow.domain.symbol.domain.Symbol;
import com.coinflow.domain.symbol.domain.vo.MarketType;
import com.coinflow.domain.symbol.repository.SymbolRepository;
import com.coinflow.domain.ohlc.repository.LiveKlineRepository;
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
import java.util.concurrent.Executor;
 
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
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
 
    @MockitoBean
    private SymbolRepository symbolRepository;
 
    @MockitoBean
    private Ohlc1mRepository ohlc1mRepository;
 
    @MockitoBean
    private com.coinflow.domain.ohlc.service.Ohlc1mService ohlc1mService;
 
    @MockitoBean
    private com.coinflow.domain.ohlc.service.Ohlc5mService ohlc5mService;
 
    @MockitoBean
    private com.coinflow.domain.ohlc.service.Ohlc30mService ohlc30mService;
 
    @Autowired
    @Qualifier("dbPersistExecutor")
    private Executor dbPersistExecutor;
 
    @MockitoBean
    private KlineAggregatorService klineAggregatorService;
 
    @MockitoBean
    private LiveKlineRepository liveKlineRepository;
 
    @MockitoBean
    private KlineBroadcaster klineBroadcaster;
 
    @MockitoBean
    private TickerBroadcaster tickerBroadcaster;
 
    private Symbol savedSymbol;
 
    @BeforeEach
    void setUp() {
        savedSymbol = Symbol.builder()
                .symbol("btcusdt")
                .exchange("BINANCE")
                .name("btcusdt")
                .active(true)
                .marketType(MarketType.SPOT)
                .build();
 
        // 모든 테스트 시나리오에서 공통으로 심볼 조회가 가능하도록 설정
        when(symbolRepository.findBySymbol(any())).thenReturn(java.util.Optional.of(savedSymbol));
    }
 
    @Test
    @DisplayName("100개 종목 동시 마감(300건 저장) 시나리오에서 스레드 풀 움직임 모니터링")
    void monitorAsyncSpikeLoad() throws InterruptedException {
        // [준비] 100개 종목 * 3개 타입 = 300건의 저장 요청 시뮬레이션 데이터
        int symbolCount = 100;
        int typesPerSymbol = 3;
        int totalRequests = symbolCount * typesPerSymbol;
 
        // DB I/O 지연(50ms) 시뮬레이션 설정 (모든 캔들 타입에 적용)
        doAnswer(invocation -> {
            Thread.sleep(50);
            return null;
        }).when(ohlc1mService).applyAndSave(any(), any(), any(), any(), any(), any(), anyLong());
 
        doAnswer(invocation -> {
            Thread.sleep(50);
            return null;
        }).when(ohlc5mService).applyAndSave(any(), any(), any(), any(), any(), any(), anyLong());
 
        doAnswer(invocation -> {
            Thread.sleep(50);
            return null;
        }).when(ohlc30mService).applyAndSave(any(), any(), any(), any(), any(), any(), anyLong());
 
 
        KlineSnapshot snapshot = new KlineSnapshot(100L, Instant.now().getEpochSecond(),
                BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE, 0, true);
        ClosedKlineSnapshot closedSnapshot = new ClosedKlineSnapshot("M1", snapshot);
 
        ThreadPoolTaskExecutor executor = (ThreadPoolTaskExecutor) dbPersistExecutor;
 
        log.info("=== [성능 테스트 시작] 시나리오: 100개 종목 3종 캔들 동시 마감 (총 {}건) ===", totalRequests);
        log.info("현재 설정 - Core: {}, Max: {}, Queue: {}", 
                executor.getCorePoolSize(), executor.getMaxPoolSize(), executor.getQueueCapacity());
 
        StopWatch submitWatch = new StopWatch();
        submitWatch.start();
 
        // 300개 비동기 요청 제출 (Submit)
        for (int i = 0; i < totalRequests; i++) {
            dbPersistService.persistClosedCandleAsync("btcusdt", closedSnapshot);
            
            // 50개마다 스레드 풀 상태 로깅
            if ((i + 1) % 50 == 0) {
                logThreadPoolStatus(executor, "[비동기 요청 제출 중 (" + (i + 1) + ")]");
            }
        }
        submitWatch.stop();
 
        log.info(">>> [제출 완료] 메인 스레드 요청 투입 소요 시간: {}ms", submitWatch.getTotalTimeMillis());
        logThreadPoolStatus(executor, "[투입 완료 직후]");
 
        if (submitWatch.getTotalTimeMillis() > 100) {
            log.warn("!!! [주의] 제출 지연 발생! 큐가 꽉 찼거나 CallerRunsPolicy가 발동했을 가능성이 있습니다.");
        }
 
        // 모든 작업이 완료(Queue Drain)될 때까지 대기 및 상태 모니터링
        StopWatch drainWatch = new StopWatch();
        drainWatch.start();
        while (executor.getActiveCount() > 0 || !executor.getThreadPoolExecutor().getQueue().isEmpty()) {
            logThreadPoolStatus(executor, "[대기열 소진 중...]");
            Thread.sleep(200); // 모니터링 간격 조정
        }
        drainWatch.stop();
 
        log.info(">>> [모든 작업 완료] 총 대기열 소진 시간: {}ms", drainWatch.getTotalTimeMillis());
        log.info("==================================================================");
    }
 
    private void logThreadPoolStatus(ThreadPoolTaskExecutor executor, String phase) {
        log.info("{} 활성 스레드: {}, 대기 큐: {}, 현재 풀 크기: {}", 
                phase,
                executor.getActiveCount(),
                executor.getThreadPoolExecutor().getQueue().size(),
                executor.getPoolSize());
    }
 
    @Test
    @DisplayName("부하 중첩 시뮬레이션: 마감 300건 + 일반 틱 1,000건 발생 시 실시간성 지연 측정")
    void simulateSpikeLoadPersistence() throws InterruptedException {
        int spikeSaves = 300;   // 30분 주기로 몰리는 1/5/30분 캔들 총 300건
        int normalTicks = 1000; // 평상시 가격 업데이트를 위한 실시간 데이터 흐름
        StopWatch watch = new StopWatch();
 
        // [1] 동기 방식 시뮬레이션: 300개 저장 부하가 뒤따르는 1,000개 틱 처리에 미치는 영향
        doAnswer(invocation -> {
            Thread.sleep(50); // DB 저장 지연 시뮬레이션
            return null;
        }).when(ohlc1mService).applyAndSave(any(), any(), any(), any(), any(), any(), anyLong());
 
        log.info(">>> [동기 방식] 300건 마감 부하 발생 후 실시간 틱 데이터 1,000건 처리 시작...");
        watch.start("Sync_Spike");
        for (int i = 0; i < spikeSaves; i++) {
            ohlc1mService.applyAndSave(savedSymbol, LocalDateTime.now(), 
                BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE, 100L);
        }
        for (int i = 0; i < normalTicks; i++) {
            // 실시간 가격 브로드캐스팅 로직 (저장 없이 로그만 가정)
        }
        watch.stop();
        long syncTime = watch.lastTaskInfo().getTimeMillis();
        log.info(">>> [동기 방식] 총 지합 소요 시간: {}ms (약 15초간 현재가 먹통 발생)", syncTime);
 
        // [2] 비동기 방식 시뮬레이션: 300개 부하를 던지고 즉시 1,000개 틱 처리 시작
        log.info(">>> [비동기 방식] 300건 마감 부하 발생 후 실시간 틱 데이터 1,000건 처리 시작...");
        watch.start("Async_Spike");
        for (int i = 0; i < spikeSaves; i++) {
            dbPersistService.persistClosedCandleAsync("btcusdt", new ClosedKlineSnapshot("M1", new KlineSnapshot(100L, 1710777600L, BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE, 0, true)));
        }
        for (int i = 0; i < normalTicks; i++) {
            // 실시간 가격 브로드캐스팅 로직 즉시 수행
        }
        watch.stop();
        long asyncTime = watch.lastTaskInfo().getTimeMillis();
        log.info(">>> [비동기 방식] 총 지합 소요 시간: {}ms (지연 없이 현재가 지속 처리)", asyncTime);
 
        log.info("========================================");
        log.info("동기 방식 {}ms, 비동기 {}ms.", syncTime, asyncTime);
    }
}
