package com.coinflow.aggregation.process.aggregate;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

class VolumeScalingTest {

    @Test
    @DisplayName("IEEE 754: Double 타입 연산 시 부동소수점 오차 발생 검증")
    void double_FloatingPointError_Test() {
        // Given
        double volume1 = 0.1;
        double volume2 = 0.2;

        // When
        double sum = volume1 + volume2;

        // Then
        // 0.1 + 0.2는 0.3이 아니라 0.30000000000000004 가 됨을 증명
        System.out.println("0.1 + 0.2 (double) = " + sum);
        assertThat(sum).isNotEqualTo(0.3);
        assertThat(sum).isEqualTo(0.30000000000000004);

        // 실제 틱 누적 상황 시뮬레이션 (0.0001 BTC를 10000번 더하면?)
        double tickVolume = 0.0001;
        double accumulatedDouble = 0.0;
        for (int i = 0; i < 10000; i++) {
            accumulatedDouble += tickVolume;
        }

        // 정상적이라면 1.0이 나와야 하지만 오차가 누적됨
        System.out.println("0.0001 * 10000 누적 (double) = " + accumulatedDouble);
        assertThat(accumulatedDouble).isNotEqualTo(1.0);
    }

    @Test
    @DisplayName("BigDecimal: 부동소수점 오차 없이 정확한 연산 검증")
    void bigDecimal_ExactCalculation_Test() {
        // Given
        BigDecimal volume1 = new BigDecimal("0.1");
        BigDecimal volume2 = new BigDecimal("0.2");

        // When
        BigDecimal sum = volume1.add(volume2);

        // Then
        System.out.println("0.1 + 0.2 (BigDecimal) = " + sum);
        assertThat(sum).isEqualTo(new BigDecimal("0.3"));
        
        // 실제 틱 누적 상황 시뮬레이션
        BigDecimal tickVolume = new BigDecimal("0.0001");
        BigDecimal accumulatedBigDecimal = BigDecimal.ZERO;
        for (int i = 0; i < 10000; i++) {
            accumulatedBigDecimal = accumulatedBigDecimal.add(tickVolume);
        }

        System.out.println("0.0001 * 10000 누적 (BigDecimal) = " + accumulatedBigDecimal);
        assertThat(accumulatedBigDecimal.compareTo(BigDecimal.ONE)).isEqualTo(0);
    }
}
