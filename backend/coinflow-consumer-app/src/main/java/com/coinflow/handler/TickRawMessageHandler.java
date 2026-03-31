package com.coinflow.handler;

import static com.coinflow.publish.stream.RedisStreamTickPublisher.RAW_PAYLOAD_FIELD;

import com.coinflow.aggregation.service.TickProcessService;
import com.coinflow.tick.serialization.TickRawBinaryCodec;
import com.coinflow.tick.validation.TickValidator;
import java.math.BigDecimal;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.stereotype.Component;

/**
 * Redis Stream으로부터 수신한 바이너리 틱 데이터를 필드 단위로 추출하여 처리 엔진으로 전달합니다.
 * Zero-POJO 전략에 따라 TickRawEvent 객체 생성을 수동으로 방어합니다.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class TickRawMessageHandler {

    private final TickProcessService tickProcessService;

    /**
     * @return true = 처리 루틴 진입 성공
     */
    public boolean handle(Map<String, byte[]> value, String streamKey, String group, RecordId recordId) {
        try {
            byte[] rawData = value.get(RAW_PAYLOAD_FIELD);
            if (rawData == null) {
                log.warn("Missing payload field '{}' in stream record. recordId={}", RAW_PAYLOAD_FIELD, recordId);
                return false;
            }

            // 0. 프로토콜 버전 체크 (Point 1)
            byte version = TickRawBinaryCodec.extractVersion(rawData);
            if (version != TickRawBinaryCodec.PROTOCOL_VERSION) {
                log.warn("Unsupported binary protocol version. expected={}, received={}, recordId={}", 
                        TickRawBinaryCodec.PROTOCOL_VERSION, version, recordId);
                return false;
            }

            // 1. 바이너리에서 각 필드 직접 추출 (객체 생성 방지)
            String symbol = TickRawBinaryCodec.extractSymbol(rawData);
            BigDecimal price = TickRawBinaryCodec.extractPrice(rawData);
            BigDecimal quantity = TickRawBinaryCodec.extractQuantity(rawData);
            long eventTime = TickRawBinaryCodec.extractEventTime(rawData);

            // 2. 무결성 검증 (Early Validation)
            // Note: 이미 Producer에서 검증되어 직렬화되었으나, Consumer 보안을 위해 2중 방어 유지 (SRP)
            TickValidator.validate(symbol, price, quantity, eventTime);

            // 3. 집계 엔진으로 직접적인 기본형 전달 (Zero-POJO)
            tickProcessService.process(symbol, price, quantity, eventTime, streamKey, group, recordId);

            return true;
        } catch (Exception e) {
            log.error("Failed to handle raw binary tick message. recordId={}, error={}", 
                    recordId, e.getMessage());
            return false;
        }
    }
}
