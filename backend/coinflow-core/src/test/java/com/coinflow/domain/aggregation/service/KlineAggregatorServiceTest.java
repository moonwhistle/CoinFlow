package com.coinflow.domain.aggregation.service;
 
import com.coinflow.domain.aggregation.domain.vo.AggregationResult;
import com.coinflow.domain.aggregation.domain.vo.ClosedKlineSnapshot;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
 
import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
 
@Slf4j
class KlineAggregatorServiceTest {
 
    private KlineAggregatorService aggregator;
    private final String symbol = "btcusdt";
 
    @BeforeEach
    void setUp() {
        aggregator = new KlineAggregatorService();
    }
 
    @Test
    @DisplayName("정상적인 틱 발생 시 여러 시간 단위의 라이브 스냅샷이 업데이트되는지 확인")
    void testNormalTick() {
        log.info("정상 틱 처리 테스트 시작 - symbol: {}, price: 100", symbol);
        
        // [Given]
        BigDecimal price = new BigDecimal("100");
        BigDecimal qty = new BigDecimal("10");
        long epochMs = 1000_000L; // 1000초 시점 (버킷 중간)
 
        // [When]
        AggregationResult result = aggregator.processTickAndGetResult(symbol, price, qty, epochMs);
 
        // [Then]
        log.info("집계 결과 검증 - LiveSnapshots size: {}", result.liveSnapshots().size());
        
        assertAll(
            () -> assertTrue(result.closedSnapshots().isEmpty(), "중간 틱이므로 마감된 캔들이 없어야 함"),
            () -> assertTrue(result.lateUpdatedSnapshots().isEmpty(), "지연 틱이 아니므로 지연 업데이트가 없어야 함"),
            () -> assertEquals(3, result.liveSnapshots().size(), "M1, M5, M30 세 종류의 라이브 스냅샷이 생성되어야 함")
        );
 
        for (ClosedKlineSnapshot live : result.liveSnapshots()) {
            assertEquals(0, price.compareTo(live.snapshot().high()), "고가는 현재 틱 가격인 100이어야 함");
            assertFalse(live.snapshot().closed(), "라이브 캔들은 마감 상태(closed)가 아님");
        }
    }
 
    @Test
    @DisplayName("시간 버킷이 넘어가는 틱 발생 시 이전 캔들이 정상적으로 마감되는지 확인")
    void testBucketTransition() {
        log.info("버킷 전환 테스트 시작 - 60초(M1 시작) -> 121초(다음 버킷)");
 
        // [Given]
        aggregator.processTickAndGetResult(symbol, new BigDecimal("100"), new BigDecimal("10"), 60_000L);
 
        // [When]
        // 121초 시점의 틱 발생 (M1의 120-179초 버킷 진입)
        AggregationResult result = aggregator.processTickAndGetResult(symbol, new BigDecimal("105"), new BigDecimal("5"), 121_000L);
 
        // [Then]
        log.info("마감 캔들 확인 - ClosedSnapshots size: {}", result.closedSnapshots().size());
        
        assertEquals(1, result.closedSnapshots().size(), "M1 60초 버킷이 마감되어야 함");
        ClosedKlineSnapshot closedM1 = result.closedSnapshots().get(0);
        
        assertAll(
            () -> assertEquals("M1", closedM1.interval()),
            () -> assertEquals(60L, closedM1.snapshot().startTime()),
            () -> assertTrue(closedM1.snapshot().closed(), "마감 상태여야 함"),
            () -> assertEquals(0, new BigDecimal("100").compareTo(closedM1.snapshot().close()), "마감 가격은 이전 틱인 100이어야 함")
        );
    }
 
    @Test
    @DisplayName("이미 마감된 버킷의 지연 틱이 도착했을 때 버퍼를 찾아 업데이트하는지 확인")
    void testLateTick() {
        log.info("지연 틱 처리 테스트 시작");
 
        // [Given]
        // 1. 60초 버킷 데이터 누적
        aggregator.processTickAndGetResult(symbol, new BigDecimal("100"), new BigDecimal("10"), 60_000L);
        // 2. 120초 버킷 틱으로 60초 버킷 마감 및 버퍼 저장
        aggregator.processTickAndGetResult(symbol, new BigDecimal("105"), new BigDecimal("5"), 121_000L);
 
        // [When]
        // 60초 버킷에 해당하는 지연 틱(119초) 도착
        log.info("지연 틱 투입 - price: 150 (기존 고가 100 돌파)");
        AggregationResult lateResult = aggregator.processTickAndGetResult(symbol, new BigDecimal("150"), new BigDecimal("2"), 119_000L);
 
        // [Then]
        assertEquals(1, lateResult.lateUpdatedSnapshots().size(), "지연 업데이트 스냅샷이 1개 생성되어야 함");
        ClosedKlineSnapshot lateM1 = lateResult.lateUpdatedSnapshots().get(0);
        
        log.info("지연 업데이트 결과 - interval: {}, new High: {}", lateM1.interval(), lateM1.snapshot().high());
        
        assertAll(
            () -> assertEquals("M1", lateM1.interval()),
            () -> assertEquals(60L, lateM1.snapshot().startTime()),
            () -> assertEquals(0, new BigDecimal("150").compareTo(lateM1.snapshot().high()), "지연 틱에 의해 고가가 150으로 갱신되어야 함"),
            () -> assertEquals(new BigDecimal("12.00000000").stripTrailingZeros(), 
                   lateM1.snapshot().volume().stripTrailingZeros(), "거래량이 합산되어야 함 (10+2=12)")
        );
    }

    @Test
    @DisplayName("지연 틱의 버킷 버퍼가 존재하지 않거나 만료된 경우 무시되는지 확인")
    void testExpiredLateTick() {
        log.info("만료된 지연 틱 무시 테스트 시작");

        // [Given]
        // 6000초 시점의 틱으로 버킷을 상당히 진행시킴
        aggregator.processTickAndGetResult(symbol, new BigDecimal("100"), new BigDecimal("10"), 6000_000L);
 
        // [When]
        // 존재하지 않는 아주 과거의 버킷(0초)에 대한 지연 틱 발생
        AggregationResult result = aggregator.processTickAndGetResult(symbol, new BigDecimal("150"),
                new BigDecimal("10"), 10_000L);
 
        // [Then]
        log.info("지연 업데이트 확인 - lateUpdatedSnapshots size: {}", result.lateUpdatedSnapshots().size());
        assertTrue(result.lateUpdatedSnapshots().isEmpty(), "버퍼가 없으므로 지연 업데이트가 무시되어야 함");
    }
}
