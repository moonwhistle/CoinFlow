package com.coinflow.handler.binance;

import static com.coinflow.handler.binance.constant.BinanceTradeMessageFields.DATA;
import static com.coinflow.handler.binance.constant.BinanceTradeMessageFields.EVENT_TIME;
import static com.coinflow.handler.binance.constant.BinanceTradeMessageFields.PRICE;
import static com.coinflow.handler.binance.constant.BinanceTradeMessageFields.QUANTITY;
import static com.coinflow.handler.binance.constant.BinanceTradeMessageFields.SYMBOL;

import com.coinflow.tick.event.TickRawEvent;
import com.coinflow.handler.TickMessageHandler;
import com.coinflow.tick.publisher.TickPublisher;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import com.coinflow.monitoring.MetricRecorder;
import static com.coinflow.monitoring.constant.MetricConstants.WEBSOCKET_RECEIVE_COUNT;

@RequiredArgsConstructor
@Slf4j
public class BinanceTradeMessageHandler implements TickMessageHandler {

    private final ObjectMapper objectMapper;
    private final TickPublisher publisher;
    private final MetricRecorder metricRecorder;

    @Override
    public void handle(String message) {
        log.info("Received raw message: {}", message);
        metricRecorder.increment(WEBSOCKET_RECEIVE_COUNT);
        try {
            JsonNode root = objectMapper.readTree(message);
            JsonNode data = root.get(DATA);

            TickRawEvent event = new TickRawEvent(
                    data.get(SYMBOL).asText().toLowerCase(),
                    new BigDecimal(data.get(PRICE).asText()),
                    new BigDecimal(data.get(QUANTITY).asText()),
                    Instant.ofEpochMilli(data.get(EVENT_TIME).asLong()),
                    null // streamId는 Collector 단계에서 아직 존재하지 않음 (publish 이전)
            );

            publisher.publish(event);
        } catch (Exception e) {
            log.warn(
                    "Failed to parse binance trade message. message={}",
                    message,
                    e);
        }
    }
}
