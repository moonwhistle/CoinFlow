package com.coinflow.aggregation.infrastructure.scheduler;

import com.coinflow.aggregation.process.aggregate.AggregateKey;
import com.coinflow.aggregation.process.aggregate.OhlcAccumulator;
import com.coinflow.aggregation.process.store.Ohlc1mAggregationStore;
import com.coinflow.aggregation.process.time.BucketCloseChecker;
import com.coinflow.domain.ohlc.constant.OhlcInterval;
import com.coinflow.domain.ohlc.domain.Ohlc1m;
import com.coinflow.domain.ohlc.repository.OhlcLiveSnapshotRepository;
import com.coinflow.domain.symbol.domain.Symbol;
import com.coinflow.domain.symbol.service.SymbolService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class Ohlc1mSnapshotScheduler {

    private static final OhlcInterval INTERVAL = OhlcInterval.M1;

    private final Ohlc1mAggregationStore store;
    private final OhlcLiveSnapshotRepository snapshotRepository;
    private final BucketCloseChecker bucketCloseChecker;
    private final SymbolService symbolService;

    // Flush is running every 1000ms. We can run snapshot offloading staggered or at
    // the same interval.
    @Scheduled(fixedDelay = 1000)
    public void offloadLiveSnapshots() {
        for (AggregateKey key : store.keysSnapshot()) {

            // Only snapshot OPEN buckets. Closed ones are flushed to DB and handled by
            // Ohlc1mFlushScheduler.
            boolean isOpen = bucketCloseChecker.isOpen(INTERVAL, key.bucket());
            log.debug("SnapshotScheduler: key={}, isOpen={}", key, isOpen);
            if (!isOpen) {
                continue;
            }

            OhlcAccumulator acc = store.peek(key);
            if (acc == null) {
                continue;
            }

            try {
                // To create Ohlc1m we need the Symbol
                log.debug("SnapshotScheduler: looking up symbol for id={}", key.symbolId());
                Symbol symbol = symbolService.findSymbol(key.symbolId()); // Note: aggregate key holds symbolId, need

                Ohlc1m liveCandle = Ohlc1m.builder()
                        .symbol(symbol)
                        .bucketTime(key.bucket())
                        .open(acc.getOpen())
                        .high(acc.getHigh())
                        .low(acc.getLow())
                        .close(acc.getClose())
                        .volume(acc.getVolume())
                        .build();

                log.debug("SnapshotScheduler: saving snapshot to Redis for symbolId={}", key.symbolId());
                snapshotRepository.save(key.symbolId(), INTERVAL, liveCandle);

            } catch (Exception e) {
                log.error(
                        "Failed to offload live snapshot: symbol={}, bucket={}",
                        key.symbolId(),
                        key.bucket(),
                        e);
            }
        }
    }
}
