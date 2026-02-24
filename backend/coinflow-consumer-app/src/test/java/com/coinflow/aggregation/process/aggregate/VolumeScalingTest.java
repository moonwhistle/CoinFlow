package com.coinflow.aggregation.process.aggregate;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.AssertionsForClassTypes.assertThatThrownBy;

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
        // 예외가 발생하지 않고, 음수로 순환(wrap-around)됨을 증명
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
        // 최대치에서 1을 더하면 즉시 ArithmeticException 예외가 발생해야 함
        assertThatThrownBy(() -> Math.addExact(maxVolume, tickVolume))
                .isInstanceOf(ArithmeticException.class)
                .hasMessageContaining("long overflow");
    }
}
