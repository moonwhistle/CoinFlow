package com.coinflow.aggregation.service.ingest;

import com.coinflow.aggregation.service.kline.KlineAggregator;
import com.coinflow.aggregation.service.kline.KlineAggregator.AggregationResult;
import com.coinflow.aggregation.service.kline.KlineAggregator.ClosedKlineSnapshot;
import com.coinflow.aggregation.service.kline.KlineSnapshotBroadcaster;
import com.coinflow.aggregation.service.kline.KlineState.KlineSnapshot;
import com.coinflow.aggregation.service.ticker.TickerBroadcaster;
import com.coinflow.domain.ohlc.service.Ohlc1mService;
import com.coinflow.domain.ohlc.service.Ohlc30mService;
import com.coinflow.domain.ohlc.service.Ohlc5mService;
import com.coinflow.domain.symbol.domain.Symbol;
import com.coinflow.domain.symbol.domain.vo.MarketType;
import com.coinflow.domain.symbol.repository.SymbolRepository;
import com.coinflow.domain.symbol.service.SymbolService;
import com.coinflow.tick.event.TickRawEvent;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.util.StopWatch;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@Slf4j
@SpringBootTest
@ActiveProfiles("test")
public class TickProcessAsyncPerformanceTest {

    @Autowired
    private TickProcessService tickProcessService;

    @Autowired
    private Ohlc1mService ohlc1mService;

    @Autowired
    private SymbolRepository symbolRepository;

    @MockitoBean
    private KlineAggregator klineAggregator;

    @MockitoBean
    private KlineSnapshotBroadcaster klineBroadcaster;

    @MockitoBean
    private TickerBroadcaster tickerBroadcaster;

    @MockitoBean
    private SymbolService symbolService;

    @MockitoBean
    private Ohlc5mService ohlc5mService;

    @MockitoBean
    private Ohlc30mService ohlc30mService;

    @Test
    @DisplayName("실제 DB 저장 상황에서 비동기 처리가 Redis Consume 속도에 미치는 영향 측정")
    void measureAsyncPerformance() throws InterruptedException {
        // [준비] 테스트 데이터 설정 및 DB 초기화
        Symbol testSymbol = Symbol.builder()
                .symbol("btcusdt")
                .exchange("BINANCE")
                .name("Bitcoin/USDT")
                .active(true)
                .marketType(MarketType.SPOT)
                .build();
        testSymbol = symbolRepository.save(testSymbol);
        
        final Symbol savedSymbol = testSymbol;
        when(symbolService.findBySymbol(anyString())).thenReturn(savedSymbol);
        
        LocalDateTime bucketTime = LocalDateTime.now();
        
        KlineSnapshot snapshot = new KlineSnapshot(100L, 159L, BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE,
                BigDecimal.ONE, BigDecimal.ONE, 0, true);
        ClosedKlineSnapshot closedSnapshot = new ClosedKlineSnapshot("M1", snapshot);
        AggregationResult result = new AggregationResult(List.of(closedSnapshot), List.of(), List.of());
        
        when(klineAggregator.processTickAndGetResult(eq("btcusdt"), any(), any(), anyLong())).thenReturn(result);
        TickRawEvent event = new TickRawEvent("btcusdt", BigDecimal.valueOf(50000), BigDecimal.valueOf(1),
                Instant.now(), "s1");

        int tickCount = 3000;

        // 1. [동기 방식] 실제 DB 저장 시간 측정 (Baseline)
        log.info("[1/3] 동기 방식 측정 시작: {}회의 실제 DB INSERT 수행", tickCount);
        StopWatch syncWatch = new StopWatch();
        syncWatch.start();
        for (int i = 0; i < tickCount; i++) {
            ohlc1mService.applyAndSave(savedSymbol, bucketTime.plusMinutes(i), 
                BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE, 100L);
        }
        syncWatch.stop();
        double syncTimeSec = syncWatch.getTotalTimeSeconds();

        // 2. [비동기 방식] 메인 스레드 점유 시간 측정 (Optimized)
        log.info("[2/3] 비동기 방식 측정 시작: {}회의 비동기 위임 수행", tickCount);
        StopWatch asyncWatch = new StopWatch();
        asyncWatch.start();
        for (int i = 0; i < tickCount; i++) {
            tickProcessService.process(event);
        }
        asyncWatch.stop();
        double asyncTimeSec = asyncWatch.getTotalTimeSeconds();

        log.info("[3/3] 테스트 완료: 실제 DB I/O 지연을 반영한 성능 비교");

        // 3. 결과 출력
        double improvementFactor = syncTimeSec / asyncTimeSec;
        double syncUps = tickCount / syncTimeSec;
        double asyncUps = tickCount / asyncTimeSec;

        log.info("===============================================================");
        log.info("테스트 시나리오: 3,000건의 틱 데이터 저장 처리 (종목이 늘었을 경우를 가정하여 측정)");
        log.info("실제 운영 환경: 약 1,200~3,000 TPS 트래픽 중 1/5/30분 되는 시점만 DB 저장을 트리거");
        log.info("테스트 목적: DB I/O가 발생하는 그 '한 순간'이 전체 소비 속도에 미치는 영향 측정");
        log.info("---------------------------------------------------------------");
        log.info("기존 [동기] 방식 처리량: {} TPS (저장 발생 시 메인 스레드 대기)", String.format("%.1f", syncUps));
        log.info("현재 [비동기] 방식 처리량: {} TPS (저장 발생 시 즉시 위임)", String.format("%.1f", asyncUps));
        log.info("---------------------------------------------------------------");
        log.info("메인 스레드 점유 시간(틱당): 기존 {}ms -> 개선 {}ms",
                String.format("%.4f", (syncTimeSec * 1000.0) / tickCount),
                String.format("%.4f", (asyncTimeSec * 1000.0) / tickCount));
        log.info("개선: DB 저장 부하와 상관없이 약 {}배 높은 메시지 소비 속도 유지", String.format("%.1f", improvementFactor));
        log.info("---------------------------------------------------------------");
        log.info("분석: 실제 상황에서는 저장이 매번 일어나지 않지만, 동기 방식에서는 저장이 발생하는 시점 마다");
        log.info("Redis 소비 큐에 지연 발생. 비동기 전환은 해당 시점의 병목을 제거.");
        log.info("===============================================================");
    }
}
