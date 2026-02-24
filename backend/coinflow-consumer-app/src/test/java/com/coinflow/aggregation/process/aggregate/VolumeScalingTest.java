package com.coinflow.aggregation.process.aggregate;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class VolumeScalingTest {

    @Test
    @DisplayName("IEEE 754: Double 타입 단순 연산 시 부동소수점 오차 발생 검증")
    void double_SimpleAddition_FloatingPointError_Test() {
        // Given
        double volume1 = 0.1;
        double volume2 = 0.2;

        // When
        double sum = volume1 + volume2;

        // Then
        assertThat(sum).isNotEqualTo(0.3);
        assertThat(sum).isEqualTo(0.30000000000000004);
    }

    @Test
    @DisplayName("IEEE 754: Double 타입 고빈도 누적 시 부동소수점 오차 누적 검증")
    void double_LoopAddition_FloatingPointError_Test() {
        // Given
        double tickVolume = 0.0001;
        int iterationCount = 10000;
        double accumulatedDouble = 0.0;

        // When
        for (int i = 0; i < iterationCount; i++) {
            accumulatedDouble += tickVolume;
        }

        // Then
        assertThat(accumulatedDouble).isNotEqualTo(1.0);
        assertThat(accumulatedDouble).isEqualTo(0.9999999999999062);
    }

    @Test
    @DisplayName("BigDecimal: 단순 연산 시 부동소수점 오차 없이 정확한 연산 검증")
    void bigDecimal_SimpleAddition_ExactCalculation_Test() {
        // Given
        BigDecimal volume1 = new BigDecimal("0.1");
        BigDecimal volume2 = new BigDecimal("0.2");

        // When
        BigDecimal sum = volume1.add(volume2);

        // Then
        assertThat(sum).isEqualTo(new BigDecimal("0.3"));
    }

    @Test
    @DisplayName("BigDecimal: 고빈도 누적 시 부동소수점 오차 없이 정확한 연산 검증")
    void bigDecimal_LoopAddition_ExactCalculation_Test() {
        // Given
        BigDecimal tickVolume = new BigDecimal("0.0001");
        int iterationCount = 10000;
        BigDecimal accumulatedBigDecimal = BigDecimal.ZERO;

        // When
        for (int i = 0; i < iterationCount; i++) {
            accumulatedBigDecimal = accumulatedBigDecimal.add(tickVolume);
        }

        // Then
        assertThat(accumulatedBigDecimal.compareTo(BigDecimal.ONE)).isEqualTo(0);
    }

    @Test
    @DisplayName("Long Scaling: 단순 `+` 연산 시 오버플로우 발생 검증 (Silent Failure)")
    void long_SimpleAddition_Overflow_Test() {
        // Given
        long maxVolume = Long.MAX_VALUE;
        long tickVolume = 1L;

        // When
        long result = maxVolume + tickVolume;

        // Then
        assertThat(result).isNegative();
        assertThat(result).isEqualTo(Long.MIN_VALUE);
    }

    @Test
    @DisplayName("Long Scaling: Math.addExact 연산 시 오버플로우 방어(예외 발생) 검증")
    void long_AddExact_OverflowProtection_Test() {
        // Given
        long maxVolume = Long.MAX_VALUE;
        long tickVolume = 1L;

        // When & Then
        assertThatThrownBy(() -> Math.addExact(maxVolume, tickVolume))
                .isInstanceOf(ArithmeticException.class)
                .hasMessageContaining("long overflow");
    }

    @Test
    @DisplayName("성능 비교: BigDecimal vs Long (1천만 번 누적 연산)")
    void performance_Benchmark_BigDecimal_Vs_Long_Test() {
        // Given
        int iterationCount = 10_000_000;

        BigDecimal bdTickVolume = new BigDecimal("0.0001");
        BigDecimal accumulatedBd = BigDecimal.ZERO;

        // 0.0001을 10^8 스케일링한 값 = 10000L
        long longTickVolume = 10000L;
        long accumulatedLong = 0L;

        // When - 1. BigDecimal 성능 측정
        long startBd = System.currentTimeMillis();
        for (int i = 0; i < iterationCount; i++) {
            accumulatedBd = accumulatedBd.add(bdTickVolume);
        }
        long endBd = System.currentTimeMillis();
        long bdDuration = endBd - startBd;

        // When - 2. Long 성능 측정
        long startLong = System.currentTimeMillis();
        for (int i = 0; i < iterationCount; i++) {
            accumulatedLong = Math.addExact(accumulatedLong, longTickVolume);
        }
        long endLong = System.currentTimeMillis();
        long longDuration = endLong - startLong;

        // Then - 연산 결과 검증 (1000)
        assertThat(accumulatedBd.compareTo(new BigDecimal("1000"))).isEqualTo(0);
        assertThat(accumulatedLong).isEqualTo(1000_00000000L); // 1000 * 10^8

        // Then - 성능 비교 검증 (Long이 더 빨라야 함)
        // 블로그 포스팅 수치 참고를 위해 콘솔 출력 허용
        System.out.println("====== 1천만 번 누적 연산 벤치마크 ======");
        System.out.println("BigDecimal 누적 연산 소요 시간: " + bdDuration + "ms");
        System.out.println("Long (Math.addExact) 누적 연산 소요 시간: " + longDuration + "ms");

        assertThat(longDuration).isLessThan(bdDuration);
    }
}
