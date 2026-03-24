package com.coinflow.domain.aggregation.domain;
 
import com.coinflow.domain.aggregation.domain.vo.KlineSnapshot;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
 
import java.math.BigDecimal;
import static org.junit.jupiter.api.Assertions.*;
 
@Slf4j
class MutableKlineSnapshotTest {
 
    @Test
    @DisplayName("지연된 틱 적용 시 OHLCV 데이터가 올바르게 갱신되고 시가는 유지되는지 확인")
    void testApplyLateTick() {
        log.info("MutableKlineSnapshot 지연 틱 적용 테스트");
 
        // [Given]
        KlineSnapshot initial = new KlineSnapshot(
                1000L, 1059L, 
                new BigDecimal("100"), // 시가
                new BigDecimal("110"), // 고가
                new BigDecimal("90"),  // 저가
                new BigDecimal("105"), // 종가
                new BigDecimal("500"), // 거래량
                10, // 거래 횟수
                true // 마감 여부
        );
        MutableKlineSnapshot mutable = new MutableKlineSnapshot(initial);
 
        // [When]
        log.info("현재 고가(110)보다 높은 틱(115) 적용 - 50 qty");
        mutable.applyLateTick(new BigDecimal("115"), 50_000_000_00L); // 50 qty (scaled)
 
        // [Then]
        KlineSnapshot result = mutable.toSnapshot();
        log.info("업데이트 후 결과 - High: {}, Volume: {}", result.high(), result.volume());
        
        assertAll(
            () -> assertEquals(0, new BigDecimal("100").compareTo(result.open()), "시가는 변하지 않아야 함"),
            () -> assertEquals(0, new BigDecimal("115").compareTo(result.high()), "고가가 115로 갱신되어야 함"),
            () -> assertEquals(new BigDecimal("550").stripTrailingZeros(), result.volume().stripTrailingZeros(), "거래량이 550으로 합산되어야 함"),
            () -> assertEquals(11, result.trades(), "거래 횟수가 11로 증가해야 함")
        );
    }

    @Test
    @DisplayName("만료 시간(TTL) 기반의 만료 여부 확인 테스트")
    void testIsExpired() {
        log.info("MutableKlineSnapshot 만료 테스트");

        // [Given]
        KlineSnapshot initial = new KlineSnapshot(1000L, 1059L, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, 0, true);
        MutableKlineSnapshot mutable = new MutableKlineSnapshot(initial);
 
        // [When / Then]
        log.info("정상 TTL(5000ms) 및 과거 TTL(-1ms) 검증");
        assertAll(
            () -> assertFalse(mutable.isExpired(5000L), "생성 직후에는 5초 TTL에 의해 만료되지 않아야 함"),
            () -> assertTrue(mutable.isExpired(-1L), "과거 시점의 TTL은 즉시 만료된 것으로 간주되어야 함")
        );
    }
}
