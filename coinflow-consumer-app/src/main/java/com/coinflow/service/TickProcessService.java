package com.coinflow.service;

import com.coinflow.domain.tick.event.TickRawEvent;
import com.coinflow.process.service.Ohlc1mAggregationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@Slf4j
public class TickProcessService {

    private final Ohlc1mAggregationService ohlc1MAggregationService;

    public void process(TickRawEvent event) {
        /*log.info(
                "[Consumer] Processing tick event - symbol={}, price={}, eventTime={}",
                event.symbol(),
                event.price(),
                event.eventTime()
        );*/

        try {
            ohlc1MAggregationService.process(event);
            log.debug("[Consumer] Successfully processed tick event - symbol={}", event.symbol());
        } catch (Exception e) {
            log.error(
                    "[Consumer] Failed to process tick event - symbol={}, price={}",
                    event.symbol(),
                    event.price(),
                    e
            );

            throw e;
        }
    }
}
