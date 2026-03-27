package com.coinflow.handler.binance;

import static com.coinflow.handler.binance.constant.BinanceTradeMessageFields.DATA;
import static com.coinflow.handler.binance.constant.BinanceTradeMessageFields.EVENT_TIME;
import static com.coinflow.handler.binance.constant.BinanceTradeMessageFields.PRICE;
import static com.coinflow.handler.binance.constant.BinanceTradeMessageFields.QUANTITY;
import static com.coinflow.handler.binance.constant.BinanceTradeMessageFields.SYMBOL;
import static com.coinflow.monitoring.constant.MetricConstants.WEBSOCKET_RECEIVE_COUNT;

import com.coinflow.handler.TickMessageHandler;
import com.coinflow.monitoring.MetricRecorder;
import com.coinflow.tick.publisher.TickPublisher;
import com.coinflow.tick.serialization.TickRawBinaryCodec;
import com.coinflow.tick.validation.TickValidator;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 바이낸스 Websocket Trade 메시지를 처리하여 Redis Stream으로 전송하는 핸들러입니다.
 * Jackson Stream API를 사용하여 JsonNode 트리 생성 없이 필드 값을 즉시 추출하여 Zero-POJO 및 최소 메모리 할당을 구현합니다.
 */
@RequiredArgsConstructor
@Slf4j
public class BinanceTradeMessageHandler implements TickMessageHandler {

    private final ObjectMapper objectMapper;
    private final TickPublisher publisher;
    private final MetricRecorder metricRecorder;

    @Override
    public void handle(String message) {
        metricRecorder.increment(WEBSOCKET_RECEIVE_COUNT);
        
        try (JsonParser parser = objectMapper.createParser(message)) {
            String symbol = null;
            BigDecimal price = null;
            BigDecimal quantity = null;
            long eventTime = 0;

            // 1. JSON 스트리밍 파싱 (JsonNode 생성 방지)
            while (parser.nextToken() != JsonToken.END_OBJECT) {
                String fieldName = parser.currentName();
                if (fieldName == null) continue;

                if (DATA.equals(fieldName)) {
                    parser.nextToken(); // START_OBJECT {
                    while (parser.nextToken() != JsonToken.END_OBJECT) {
                        String dataFieldName = parser.currentName();
                        parser.nextToken(); // 필드 값으로 이동
                        
                        if (SYMBOL.equals(dataFieldName)) symbol = parser.getText().toLowerCase();
                        else if (PRICE.equals(dataFieldName)) price = new BigDecimal(parser.getText());
                        else if (QUANTITY.equals(dataFieldName)) quantity = new BigDecimal(parser.getText());
                        else if (EVENT_TIME.equals(dataFieldName)) eventTime = parser.getLongValue();
                    }
                }
            }

            // 2. 유효성 검증 및 바이너리 전송
            if (symbol != null && price != null && quantity != null && eventTime != 0) {
                TickValidator.validate(symbol, price, quantity, eventTime);
                byte[] rawData = TickRawBinaryCodec.encode(symbol, price, quantity, eventTime);
                publisher.publish(rawData);
                log.debug("Successfully published streaming binary tick: {}", symbol);
            }

        } catch (Exception e) {
            log.warn("Failed to stream binance trade message. error={}", e.getMessage());
        }
    }
}
