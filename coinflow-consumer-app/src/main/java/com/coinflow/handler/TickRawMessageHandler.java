package com.coinflow.handler;

import static com.coinflow.domain.tick.event.constant.TickStreamFields.EVENT_TIME;
import static com.coinflow.domain.tick.event.constant.TickStreamFields.PRICE;
import static com.coinflow.domain.tick.event.constant.TickStreamFields.QUANTITY;
import static com.coinflow.domain.tick.event.constant.TickStreamFields.SYMBOL;

import com.coinflow.domain.tick.event.TickRawEvent;
import com.coinflow.process.TickProcessor;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class TickRawMessageHandler {

    private final TickProcessor tickProcessor;

    public void handle(MapRecord<String, String, String> record) {
        Map<String, String> value = record.getValue();

        String symbol = value.get(SYMBOL);
        String priceStr = value.get(PRICE);
        String quantityStr = value.get(QUANTITY);
        String eventTimeStr = value.get(EVENT_TIME);

        try {
            TickRawEvent event = new TickRawEvent(
                    symbol,
                    new BigDecimal(priceStr),
                    new BigDecimal(quantityStr),
                    Instant.parse(eventTimeStr)
            );

            tickProcessor.process(event);
        } catch (Exception e) {
            // consumer가 ACK/재처리 정책 결정
            log.error(
                    "Malformed tick message. symbol={}, price={}, quantity={}, eventTime={}",
                    symbol, priceStr, quantityStr, eventTimeStr, e
            );
            throw e;
        }
    }
}
