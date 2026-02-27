package com.coinflow.aggregation.service.ingest;

import com.coinflow.aggregation.service.flush.Ohlc1mAggregationService;
import com.coinflow.aggregation.service.kline.KlineAggregator;
import com.coinflow.aggregation.service.kline.KlineAggregator.ClosedKlineSnapshot;
import com.coinflow.aggregation.service.kline.KlineSnapshotBroadcaster;
import com.coinflow.tick.event.TickRawEvent;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@Slf4j
public class TickProcessService {

    private final Ohlc1mAggregationService ohlc1MAggregationService;
    private final KlineAggregator klineAggregator;
    private final KlineSnapshotBroadcaster klineSnapshotBroadcaster;

    public void process(TickRawEvent event) {
        try {
            // DB Aggregation
            ohlc1MAggregationService.process(event);

            // In-Memory Real-time Kline Aggregation -> Broadcast if bucket transitions
            List<ClosedKlineSnapshot> closed = klineAggregator.processTickAndGetClosed(
                    event.symbol(),
                    event.price(),
                    event.quantity(),
                    event.eventTime().toEpochMilli());

            for (ClosedKlineSnapshot c : closed) {
                klineSnapshotBroadcaster.broadcast(event.symbol(), c.interval(), c.snapshot());
            }
            log.debug("[Consumer] Successfully processed tick event - symbol={}", event.symbol());
        } catch (Exception e) {
            log.error(
                    "[Consumer] Failed to process tick event - symbol={}, price={}",
                    event.symbol(),
                    event.price(),
                    e);

            throw e;
        }
    }
}
