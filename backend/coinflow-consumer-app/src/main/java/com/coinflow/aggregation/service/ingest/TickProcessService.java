package com.coinflow.aggregation.service.ingest;

import com.coinflow.aggregation.service.kline.KlineAggregator;
import com.coinflow.aggregation.service.kline.KlineAggregator.AggregationResult;
import com.coinflow.aggregation.service.kline.KlineAggregator.ClosedKlineSnapshot;
import com.coinflow.aggregation.service.kline.KlineSnapshotBroadcaster;
import com.coinflow.aggregation.service.ticker.TickerBroadcaster;
import com.coinflow.domain.ohlc.service.Ohlc1mService;
import com.coinflow.domain.ohlc.service.Ohlc30mService;
import com.coinflow.domain.ohlc.service.Ohlc5mService;
import com.coinflow.domain.symbol.domain.Symbol;
import com.coinflow.domain.symbol.service.SymbolService;
import com.coinflow.tick.event.TickRawEvent;
import com.coinflow.event.ticker.TickerEvent;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
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
    private final SymbolService symbolService;

    // Direct DB Services
    private final Ohlc1mService ohlc1mService;
    private final Ohlc5mService ohlc5mService;
    private final Ohlc30mService ohlc30mService;

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

            // Broadcast & Save Live Snapshots (M1, M5, M30 always updated on tick)
            if (!result.liveSnapshots().isEmpty()) {
                for (ClosedKlineSnapshot c : result.liveSnapshots()) {
                    klineBroadcaster.broadcastAndSave(event.symbol(), c.interval(), c.snapshot());
                }
            }

            // Broadcast & Save Closed Snapshots if bucket transitions occurred
            for (ClosedKlineSnapshot c : result.closedSnapshots()) {
                // 1. WebSocket Broadcast
                klineBroadcaster.broadcastAndSave(event.symbol(), c.interval(), c.snapshot());

                // 2. Exact SSOT DB Persistence (No more Schedulers)
                persistClosedCandleToDb(event.symbol(), c);
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

    private void persistClosedCandleToDb(String symbolCode, ClosedKlineSnapshot closedSnapshot) {
        Symbol symbol = symbolService.findBySymbol(symbolCode);
        LocalDateTime bucketTime = LocalDateTime.ofEpochSecond(closedSnapshot.snapshot().startTime(), 0,
                ZoneOffset.UTC);
        long volume = closedSnapshot.snapshot().volume().setScale(0, RoundingMode.DOWN).longValue();

        switch (closedSnapshot.interval()) {
            case "M1" -> ohlc1mService.applyAndSave(
                    symbol, bucketTime,
                    closedSnapshot.snapshot().open(),
                    closedSnapshot.snapshot().high(),
                    closedSnapshot.snapshot().low(),
                    closedSnapshot.snapshot().close(),
                    volume);
            case "M5" -> ohlc5mService.applyAndSave(
                    symbol, bucketTime,
                    closedSnapshot.snapshot().open(),
                    closedSnapshot.snapshot().high(),
                    closedSnapshot.snapshot().low(),
                    closedSnapshot.snapshot().close(),
                    volume);
            case "M30" -> ohlc30mService.applyAndSave(
                    symbol, bucketTime,
                    closedSnapshot.snapshot().open(),
                    closedSnapshot.snapshot().high(),
                    closedSnapshot.snapshot().low(),
                    closedSnapshot.snapshot().close(),
                    volume);
            default -> log.warn("Unknown interval for DB persistence: {}", closedSnapshot.interval());
        }
        log.debug("Persisted {} closed candle to DB for symbol={} at {}", closedSnapshot.interval(), symbolCode,
                bucketTime);
    }
}
