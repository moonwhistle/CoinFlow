package com.coinflow.handler;

import static com.coinflow.domain.tick.event.constant.TickStreamFields.EVENT_TIME;
import static com.coinflow.domain.tick.event.constant.TickStreamFields.PRICE;
import static com.coinflow.domain.tick.event.constant.TickStreamFields.QUANTITY;
import static com.coinflow.domain.tick.event.constant.TickStreamFields.SYMBOL;

import com.coinflow.domain.tick.event.TickRawEvent;
import com.coinflow.service.TickProcessService;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class TickRawMessageHandler {

    private final TickProcessService tickProcessService;

    /**
     * @return true  = 정상 처리 (ACK 가능)
     *         false = 실패 (pending 유지)
     */
    public boolean handle(Map<String, String> value) {
        try {
            TickRawEvent event = new TickRawEvent(
                    value.get(SYMBOL),
                    new BigDecimal(value.get(PRICE)),
                    new BigDecimal(value.get(QUANTITY)),
                    Instant.parse(value.get(EVENT_TIME))
            );

            tickProcessService.process(event);
            return true;

        } catch (Exception e) {
            log.error(
                    "Failed to handle tick raw message. payload={}",
                    value,
                    e
            );
            return false;
        }
    }
}
