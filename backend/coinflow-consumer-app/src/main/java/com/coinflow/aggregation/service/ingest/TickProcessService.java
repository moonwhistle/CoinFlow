package com.coinflow.aggregation.service.ingest;

import com.coinflow.aggregation.service.kline.KlineAggregator;
import com.coinflow.aggregation.service.kline.KlineAggregator.AggregationResult;
import com.coinflow.aggregation.service.kline.KlineAggregator.ClosedKlineSnapshot;
import com.coinflow.aggregation.service.kline.KlineSnapshotBroadcaster;
import com.coinflow.aggregation.service.ticker.TickerBroadcaster;
import com.coinflow.aggregation.service.persist.DbPersistService;
import com.coinflow.event.ticker.TickerEvent;
import com.coinflow.tick.event.TickRawEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@Slf4j
public class TickProcessService {

    private final KlineAggregator klineAggregator;
    private final KlineSnapshotBroadcaster klineBroadcaster;
    private final TickerBroadcaster tickerBroadcaster;
    private final DbPersistService dbPersistService;

    public void process(TickRawEvent event) {
        log.debug("Processing tick event: {}", event);

        // 1. Broadcast 100% real-time Ticker Event (0ms delay)
        TickerEvent tickerEvent = new TickerEvent(
                event.symbol(),
                event.price(),
                event.quantity(),
                event.eventTime().toEpochMilli());
        tickerBroadcaster.broadcast(tickerEvent);

        try {
            // In-Memory Real-time Kline Aggregation (SSOT track)
            AggregationResult result = klineAggregator.processTickAndGetResult(
                    event.symbol(),
                    event.price(),
                    event.quantity(),
                    event.eventTime().toEpochMilli());

            // 1. Broadcast & Save Late Snapshots
            for (ClosedKlineSnapshot c : result.lateUpdatedSnapshots()) {
                klineBroadcaster.broadcastAndSave(event.symbol(), c.interval(), c.snapshot());
                dbPersistService.persistClosedCandleAsync(event.symbol(), c);
            }

            // 2. Broadcast & Save Closed Snapshots
            for (ClosedKlineSnapshot c : result.closedSnapshots()) {
                klineBroadcaster.broadcastAndSave(event.symbol(), c.interval(), c.snapshot());
                dbPersistService.persistClosedCandleAsync(event.symbol(), c);
            }

            // 3. Broadcast & Save Live Snapshots
            if (!result.liveSnapshots().isEmpty()) {
                for (ClosedKlineSnapshot c : result.liveSnapshots()) {
                    klineBroadcaster.broadcastAndSave(event.symbol(), c.interval(), c.snapshot());
                }
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
