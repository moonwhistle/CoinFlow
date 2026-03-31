package com.coinflow.aggregation.service;

import com.coinflow.domain.aggregation.domain.vo.ClosedKlineSnapshot;
import com.coinflow.domain.aggregation.domain.vo.KlineSnapshot;
import com.coinflow.domain.aggregation.domain.vo.AggregationResult;
import com.coinflow.domain.aggregation.service.KlineAggregatorService;
import com.coinflow.domain.ohlc.repository.LiveKlineRepository;
import com.coinflow.aggregation.infrastructure.persistence.DbPersistService;
import com.coinflow.event.kline.KlineEvent;
import com.coinflow.monitoring.MetricRecorder;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static com.coinflow.monitoring.constant.MetricConstants.*;

/**
 * TickProcessService의 집계 연동 및 전파 로직을 검증하는 테스트입니다.
 */
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
    @Mock
    private ObjectMapper objectMapper;

    @InjectMocks
    private TickProcessService tickProcessService;

    private final String symbol = "btcusdt";
    private final BigDecimal price = new BigDecimal("100");
    private final BigDecimal quantity = new BigDecimal("10");
    private final long eventTime = 123456789L;
    private final RecordId recordId = RecordId.of("123-0");

    @BeforeEach
    void setUp() throws Exception {
        // MetricRecorder가 인자로 받은 Runnable을 즉시 실행하도록 설정 (Metric 측정 모킹)
        lenient().doAnswer(invocation -> {
            ((Runnable) invocation.getArgument(1)).run();
            return null;
        }).when(metricRecorder).recordTime(anyString(), any(Runnable.class), any(String[].class));

        // ObjectMapper가 null을 반환하면 broadcast/save에 null이 전달되어 검증에 실패하므로 stubbing
        lenient().when(objectMapper.writeValueAsString(any())).thenReturn("{}");
    }

    @Test
    @DisplayName("지연 틱 발생 시 캐시 저장, 전파, 비동기 DB 저장이 올바른 순서로 수행되어야 한다 (Zero-POJO)")
    void processLateTickTest() {
        // given: 지연 틱 데이터 결과 시나리오 구성
        KlineSnapshot lateSnapshot = new KlineSnapshot(100L, 159L, BigDecimal.ZERO, BigDecimal.ZERO,
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, 0, true);
        ClosedKlineSnapshot closedKlineSnapshot = new ClosedKlineSnapshot("M1", lateSnapshot);

        AggregationResult result = new AggregationResult(
                List.of(), // 신규 마감 없음
                List.of(), // 라이브 스냅샷 없음
                List.of(closedKlineSnapshot) // 지연 업데이트 스냅샷 존재
        );

        when(klineAggregatorService.processTickAndGetResult(
                eq(symbol), eq(price), eq(quantity), eq(eventTime))).thenReturn(result);

        when(dbPersistService.persistClosedCandleAsync(any(), any()))
                .thenReturn(CompletableFuture.completedFuture(null));

        // when: 기본형 파라미터를 통한 프로세스 호출
        tickProcessService.process(symbol, price, quantity, eventTime, "mystream", "mygroup", recordId);

        // then: 집계 엔진 호출 및 서비스 간 조율 결과 검증
        assertAll(
                // 1. Ticker 최신성 기반 전파 확인
                () -> verify(tickerBroadcaster, times(1)).broadcast(anyString()),
                // 2. 캐시 저장 및 브로드캐스트 전파 확인
                () -> verify(liveKlineRepository, times(1)).save(any(KlineEvent.class), anyString()),
                () -> verify(klineBroadcaster, times(1)).broadcast(any(KlineEvent.class), anyString()),
                // 3. 메인 스레드 점유 시간(나노초) 기록 확인
                () -> verify(metricRecorder, atLeastOnce()).recordTimeNanos(eq(TICK_MAIN_THREAD_LATENCY),
                        anyLong(), any(String[].class)),
                // 4. DB 비동기 저장 서비스 호출 확인
                () -> verify(dbPersistService, times(1)).persistClosedCandleAsync(eq(symbol), any()),
                // 5. 비동기 파이프라인 종료 후 Redis ACK 확인
                () -> verify(Objects.requireNonNull(redisTemplate.opsForStream()), timeout(1000))
                        .acknowledge("mystream", "mygroup", recordId)
        );
    }
}
