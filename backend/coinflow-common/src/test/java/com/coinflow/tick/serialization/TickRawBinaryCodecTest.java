package com.coinflow.tick.serialization;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class TickRawBinaryCodecTest {

    @Test
    @DisplayName("정상적인 틱 데이터를 인코딩하고 각 필드를 정확하게 추출해야 한다")
    void shouldEncodeAndExtractCorrectly() {
        // given
        String symbol = "btcusdt";
        BigDecimal price = new BigDecimal("65432.12345678");
        BigDecimal quantity = new BigDecimal("0.001234");
        long eventTime = 1711512345678L;

        // when
        byte[] encoded = TickRawBinaryCodec.encode(symbol, price, quantity, eventTime);

        // then
        assertThat(TickRawBinaryCodec.extractSymbol(encoded)).isEqualTo(symbol);
        assertThat(TickRawBinaryCodec.extractPrice(encoded)).isEqualByComparingTo(price);
        assertThat(TickRawBinaryCodec.extractQuantity(encoded)).isEqualByComparingTo(quantity);
        assertThat(TickRawBinaryCodec.extractEventTime(encoded)).isEqualTo(eventTime);
    }

    @Test
    @DisplayName("매우 높은 정밀도의 BigDecimal 데이터도 오차 없이 복원되어야 한다")
    void shouldHandleHighPrecisionBigDecimal() {
        // given
        String symbol = "ethusdt";
        // 소수점 12자리 이상의 매우 작은 값 테스트
        BigDecimal price = new BigDecimal("0.000000001234");
        BigDecimal quantity = new BigDecimal("12345678.90123456");
        long eventTime = System.currentTimeMillis();

        // when
        byte[] encoded = TickRawBinaryCodec.encode(symbol, price, quantity, eventTime);

        // then
        assertThat(TickRawBinaryCodec.extractPrice(encoded)).isEqualByComparingTo(price);
        assertThat(TickRawBinaryCodec.extractQuantity(encoded)).isEqualByComparingTo(quantity);
    }

    @Test
    @DisplayName("서로 다른 길이를 가진 심볼명을 정상적으로 처리해야 한다")
    void shouldHandleVariousSymbolLengths() {
        // given
        String shortSymbol = "a";
        String longSymbol = "abcdefghijklmnopqrstuvwxyz";
        BigDecimal price = BigDecimal.TEN;
        BigDecimal quantity = BigDecimal.ONE;
        long time = 123456789L;

        // when & then
        byte[] encodedShort = TickRawBinaryCodec.encode(shortSymbol, price, quantity, time);
        assertThat(TickRawBinaryCodec.extractSymbol(encodedShort)).isEqualTo(shortSymbol);

        byte[] encodedLong = TickRawBinaryCodec.encode(longSymbol, price, quantity, time);
        assertThat(TickRawBinaryCodec.extractSymbol(encodedLong)).isEqualTo(longSymbol);
    }

    @Test
    @DisplayName("추출 시 오프셋이 정확하여 데이터 간 간섭이 없어야 한다")
    void shouldNotHaveFieldInterference() {
        // given
        String symbol = "test";
        BigDecimal price = new BigDecimal("1.11");
        BigDecimal quantity = new BigDecimal("2.22");
        long time = 333L;

        // when
        byte[] encoded = TickRawBinaryCodec.encode(symbol, price, quantity, time);

        // then
        // 각 필드를 임의의 순서로 추출해도 독립적이어야 함
        assertThat(TickRawBinaryCodec.extractEventTime(encoded)).isEqualTo(333L);
        assertThat(TickRawBinaryCodec.extractPrice(encoded)).isEqualByComparingTo("1.11");
        assertThat(TickRawBinaryCodec.extractSymbol(encoded)).isEqualTo("test");
        assertThat(TickRawBinaryCodec.extractQuantity(encoded)).isEqualByComparingTo("2.22");
    }
}
