package com.coinflow.handler;

import static com.coinflow.tick.event.constant.TickStreamFields.EVENT_TIME;
import static com.coinflow.tick.event.constant.TickStreamFields.PRICE;
import static com.coinflow.tick.event.constant.TickStreamFields.QUANTITY;
import static com.coinflow.tick.event.constant.TickStreamFields.SYMBOL;

import com.coinflow.tick.event.TickRawEvent;
import com.coinflow.aggregation.service.ingest.TickProcessService;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class TickRawMessageHandler {

    private final TickProcessService tickProcessService;

    /**
     * @return true = 처리 루틴 진입 성공 (실제 ACK는 비동기 작업 후 발생할 수 있음)
     */
    public boolean handle(Map<String, String> value, String streamKey, String group, RecordId recordId) {
        try {
            TickRawEvent event = new TickRawEvent(
                    value.get(SYMBOL),
                    new BigDecimal(value.get(PRICE)),
                    new BigDecimal(value.get(QUANTITY)),
                    Instant.parse(value.get(EVENT_TIME)),
                    recordId.getValue());
            
            tickProcessService.process(event, streamKey, group, recordId);

            return true;
        } catch (Exception e) {
            log.error(
                    "Failed to handle tick raw message. payload={}",
                    value,
                    e);

            return false;
        }
    }
}
