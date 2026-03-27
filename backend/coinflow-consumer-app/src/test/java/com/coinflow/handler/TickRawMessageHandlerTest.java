package com.coinflow.handler;

import static com.coinflow.publish.stream.RedisStreamTickPublisher.RAW_PAYLOAD_FIELD;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;

import com.coinflow.aggregation.service.TickProcessService;
import com.coinflow.tick.serialization.TickRawBinaryCodec;
import java.math.BigDecimal;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.connection.stream.RecordId;

/**
 * TickRawMessageHandler의 바이너리 추출 및 유효성 검증 로직을 검증하는 테스트입니다.
 */
@ExtendWith(MockitoExtension.class)
class TickRawMessageHandlerTest {

    @Mock
    private TickProcessService tickProcessService;

    @InjectMocks
    private TickRawMessageHandler handler;

    @Test
    @DisplayName("바이너리 데이터를 정상적으로 추출하여 처리 엔진으로 전달해야 한다 (Zero-POJO)")
    void shouldHandleBinaryMessageCorrectly() {
        // given: 유효한 바이너리 데이터 준비
        String symbol = "btcusdt";
        BigDecimal price = new BigDecimal("65000.50");
        BigDecimal quantity = new BigDecimal("0.1");
        long eventTime = 1711512345000L;
        byte[] rawData = TickRawBinaryCodec.encode(symbol, price, quantity, eventTime);

        Map<String, byte[]> payload = Map.of(RAW_PAYLOAD_FIELD, rawData);
        RecordId recordId = RecordId.of("1711512345000-0");

        // when: 핸들러 실행
        boolean result = handler.handle(payload, "tick:raw", "group", recordId);

        // then: 데이터 추출 무결성 및 엔진 호출 확인
        assertThat(result).isTrue();
        verify(tickProcessService).process(
                eq(symbol),
                eq(price),
                eq(quantity),
                eq(eventTime),
                eq("tick:raw"),
                eq("group"),
                eq(recordId)
        );
    }

    @Test
    @DisplayName("유효하지 않은 데이터가 포함된 바이너리는 처리에 실패해야 한다")
    void shouldFailWhenDataIsInvalid() {
        // given: 가격이 0 이하인 유효하지 않은 데이터 바이너리 준비
        byte[] rawData = TickRawBinaryCodec.encode("test", BigDecimal.ZERO, BigDecimal.ONE, 123L);
        Map<String, byte[]> payload = Map.of(RAW_PAYLOAD_FIELD, rawData);

        // when: 핸들러 실행
        boolean result = handler.handle(payload, "tick:raw", "group", RecordId.autoGenerate());

        // then: 실패 결과 반환 확인
        assertThat(result).isFalse();
    }

    @Test
    @DisplayName("바이너리 필드가 누락된 레코드는 처리에 실패해야 한다")
    void shouldFailWhenPayloadIsMissing() {
        // given: 바이너리 필드('p')가 없는 페이로드 준비
        Map<String, byte[]> emptyPayload = Map.of();

        // when: 핸들러 실행
        boolean result = handler.handle(emptyPayload, "tick:raw", "group", RecordId.autoGenerate());

        // then: 실패 결과 반환 확인
        assertThat(result).isFalse();
    }
}
