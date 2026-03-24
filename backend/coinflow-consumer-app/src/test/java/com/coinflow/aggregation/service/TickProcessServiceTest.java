package com.coinflow.aggregation.service;
 
import com.coinflow.domain.aggregation.domain.vo.ClosedKlineSnapshot;
import com.coinflow.domain.aggregation.domain.vo.KlineSnapshot;
import com.coinflow.domain.aggregation.domain.vo.AggregationResult;
import com.coinflow.domain.aggregation.service.KlineAggregatorService;
import com.coinflow.domain.ohlc.repository.LiveKlineRepository;
import com.coinflow.aggregation.infrastructure.persistence.DbPersistService;
import com.coinflow.event.kline.KlineEvent;
import com.coinflow.monitoring.MetricRecorder;
import com.coinflow.tick.event.TickRawEvent;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Answers;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.core.RedisTemplate;
 
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletableFuture;
 
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
 
@Slf4j
@ExtendWith(MockitoExtension.class)
class TickProcessServiceTest {
 
    @Mock
    private KlineAggregatorService klineAggregatorService;
    @Mock
    private LiveKlineRepository liveKlineRepository;
    @Mock
    private KlineBroadcaster klineBroadcaster;
    @Mock
    private TickerBroadcaster tickerBroadcaster;
    @Mock
    private DbPersistService dbPersistService;
    @Mock(answer = Answers.RETURNS_DEEP_STUBS)
    private RedisTemplate<String, String> redisTemplate;
    @Mock
    private MetricRecorder metricRecorder;
 
    @InjectMocks
    private TickProcessService tickProcessService;
 
    private TickRawEvent testEvent;
    private RecordId recordId = RecordId.of("123-0");
 
    @BeforeEach
    void setUp() {
        testEvent = new TickRawEvent("btcusdt", new BigDecimal("100"), new BigDecimal("10"), Instant.now(), "123-0");
 
        // MetricRecorder가 인자로 받은 Runnable을 즉시 실행하도록 설정
        doAnswer(invocation -> {
            ((Runnable) invocation.getArgument(1)).run();
            return null;
        }).when(metricRecorder).recordTime(anyString(), any(Runnable.class), any(String[].class));
    }
 
    @Test
    @DisplayName("지연 틱 발생 시 캐시 저장 및 전파, 비동기 DB 저장이 모두 수행되는지 확인")
    void processLateTickTest() {
        log.info("지연 틱 시나리오 테스트 시작");
 
        // [Given]
        KlineSnapshot lateSnapshot = new KlineSnapshot(100L, 159L, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                BigDecimal.ZERO, BigDecimal.ZERO, 0, true);
        ClosedKlineSnapshot closedKlineSnapshot = new ClosedKlineSnapshot("M1", lateSnapshot);
 
        AggregationResult result = new AggregationResult(
                List.of(), // 신규 마감 없음
                List.of(), // 라이브 스냅샷 없음
                List.of(closedKlineSnapshot) // 지연 업데이트 스냅샷 존재
        );
 
        when(klineAggregatorService.processTickAndGetResult(
                eq("btcusdt"), any(), any(), anyLong())).thenReturn(result);
 
        // 비동기 저장이 완료된 것으로 시뮬레이션
        when(dbPersistService.persistClosedCandleAsync(any(), any()))
                .thenReturn(CompletableFuture.completedFuture(null));
 
        // [When]
        log.info("TickProcessService.process 호출");
        tickProcessService.process(testEvent, "mystream", "mygroup", recordId);
 
        // [Then]
        log.info("검증 단계 수행");
        assertAll(
            // 1. Ticker 실시간 전파 확인
            () -> verify(tickerBroadcaster, times(1)).broadcast(any()),
            // 2. SRP: 캐시 저장(Storage) 및 브로드캐스트(Notification) 개별 호출 확인
            () -> verify(liveKlineRepository, times(1)).save(any(KlineEvent.class)),
            () -> verify(klineBroadcaster, times(1)).broadcast(any(KlineEvent.class)),
            // 3. DB 비동기 저장 서비스 호출 확인
            () -> verify(dbPersistService, times(1)).persistClosedCandleAsync(eq("btcusdt"), eq(closedKlineSnapshot)),
            // 4. 비동기 작업 종료 후 Redis ACK 확인
            () -> verify(redisTemplate.opsForStream(), timeout(1000)).acknowledge(eq("mystream"), eq("mygroup"), eq(recordId))
        );
    }
}
