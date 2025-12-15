package com.coinflow.handler.binance;

import static com.coinflow.handler.binance.constant.BinanceTradeMessageFields.DATA;
import static com.coinflow.handler.binance.constant.BinanceTradeMessageFields.EVENT_TIME;
import static com.coinflow.handler.binance.constant.BinanceTradeMessageFields.PRICE;
import static com.coinflow.handler.binance.constant.BinanceTradeMessageFields.QUANTITY;
import static com.coinflow.handler.binance.constant.BinanceTradeMessageFields.SYMBOL;

import com.coinflow.domain.tick.event.TickRawEvent;
import com.coinflow.handler.TickMessageHandler;
import com.coinflow.domain.tick.publisher.TickPublisher;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@RequiredArgsConstructor
@Slf4j
public class BinanceTradeMessageHandler implements TickMessageHandler {

    private final ObjectMapper objectMapper;
    private final TickPublisher publisher;

    @Override
    public void handle(String message) {
        try {
            JsonNode root = objectMapper.readTree(message);
            JsonNode data = root.get(DATA);

            TickRawEvent event = new TickRawEvent(
                    data.get(SYMBOL).asText().toLowerCase(),
                    new BigDecimal(data.get(PRICE).asText()),
                    new BigDecimal(data.get(QUANTITY).asText()),
                    Instant.ofEpochMilli(data.get(EVENT_TIME).asLong())
            );

            publisher.publish(event);
        } catch (Exception e) {
            log.warn(
                    "Failed to parse binance trade message. message={}",
                    message,
                    e
            );
        }
    }
}
