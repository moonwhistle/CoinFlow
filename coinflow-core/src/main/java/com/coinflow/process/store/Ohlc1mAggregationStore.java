package com.coinflow.process.store;

import com.coinflow.domain.tick.event.TickRawEvent;
import com.coinflow.process.util.TimeBucket;
import java.time.LocalDateTime;
import org.springframework.stereotype.Component;

@Component
public class Ohlc1mAggregationStore extends BaseOhlcAggregationStore {

    @Override
    protected LocalDateTime resolveBucket(TickRawEvent event) {
        return TimeBucket.to1m(event.eventTime());
    }
}
