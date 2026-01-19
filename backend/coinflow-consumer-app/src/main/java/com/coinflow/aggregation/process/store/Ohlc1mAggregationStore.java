package com.coinflow.aggregation.process.store;

import com.coinflow.tick.event.TickRawEvent;
import com.coinflow.util.TimeBucket;
import java.time.LocalDateTime;
import org.springframework.stereotype.Component;

@Component
public class Ohlc1mAggregationStore extends BaseOhlcAggregationStore {

    @Override
    protected LocalDateTime resolveBucket(TickRawEvent event) {
        return TimeBucket.to1m(event.eventTime());
    }
}
