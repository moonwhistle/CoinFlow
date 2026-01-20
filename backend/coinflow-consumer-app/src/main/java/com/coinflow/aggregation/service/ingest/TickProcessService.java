package com.coinflow.aggregation.service.ingest;

import com.coinflow.aggregation.service.flush.Ohlc1mAggregationService;
import com.coinflow.tick.event.TickRawEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@Slf4j
public class TickProcessService {

    private final Ohlc1mAggregationService ohlc1MAggregationService;

    public void process(TickRawEvent event) {
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
