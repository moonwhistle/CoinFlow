package com.coinflow.aggregation.infrastructure.scheduler;

import com.coinflow.aggregation.process.aggregate.AggregateKey;
import com.coinflow.aggregation.process.aggregate.OhlcAccumulator;
import com.coinflow.aggregation.process.store.Ohlc1mAggregationStore;
import com.coinflow.aggregation.process.time.BucketCloseChecker;
import com.coinflow.domain.ohlc.constant.OhlcInterval;
import com.coinflow.domain.ohlc.repository.OhlcLiveSnapshotRepository;
import com.coinflow.domain.ohlc.snapshot.LiveCandleSnapshot;
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

    @Scheduled(fixedDelay = 1000)
    public void offloadLiveSnapshots() {
        for (AggregateKey key : store.keysSnapshot()) {

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
                log.debug("SnapshotScheduler: looking up symbol for id={}", key.symbolId());
                Symbol symbol = symbolService.findSymbol(key.symbolId());

                LiveCandleSnapshot snapshot = new LiveCandleSnapshot(
                        key.symbolId(),
                        symbol.getSymbol(),
                        key.bucket(),
                        acc.getOpen(),
                        acc.getHigh(),
                        acc.getLow(),
                        acc.getClose(),
                        acc.getVolume(),
                        acc.getLastStreamId());

                log.debug("SnapshotScheduler: saving snapshot to Redis for symbolId={}, lastStreamId={}",
                        key.symbolId(), acc.getLastStreamId());
                snapshotRepository.save(key.symbolId(), INTERVAL, snapshot);

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
