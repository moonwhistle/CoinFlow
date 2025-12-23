package com.coinflow.aggregation.infrastructure.scheduler;

import com.coinflow.domain.ohlc.constant.OhlcInterval;
import com.coinflow.aggregation.process.aggregate.AggregateKey;
import com.coinflow.aggregation.process.aggregate.OhlcAccumulator;
import com.coinflow.aggregation.process.time.BucketCloseChecker;
import com.coinflow.aggregation.process.store.Ohlc1mAggregationStore;
import com.coinflow.aggregation.service.flush.Ohlc1mFlushService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class Ohlc1mFlushScheduler {

    private static final OhlcInterval INTERVAL = OhlcInterval.M1;

    private final Ohlc1mAggregationStore store;
    private final Ohlc1mFlushService flushService;
    private final BucketCloseChecker bucketCloseChecker;

    @Scheduled(fixedDelay = 1000)
    public void flushClosedBuckets() {
        for (AggregateKey key : store.keysSnapshot()) {

            if (bucketCloseChecker.isOpen(INTERVAL, key.bucket())) {
                continue;
            }

            OhlcAccumulator acc = store.peek(key);
            if (acc == null) {
                store.remove(key);
                continue;
            }

            try {
                flushService.flush(key, acc);
            } catch (Exception e) {
                log.error(
                        "Failed to flush bucket: symbol={}, bucket={}",
                        key.symbolId(),
                        key.bucket(),
                        e
                );
            } finally {
                store.remove(key);
            }
        }
    }
}
