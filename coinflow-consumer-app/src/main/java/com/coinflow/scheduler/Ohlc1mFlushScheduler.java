package com.coinflow.scheduler;

import com.coinflow.process.aggregate.AggregateKey;
import com.coinflow.process.aggregate.OhlcAccumulator;
import com.coinflow.process.service.Ohlc1mFlushService;
import com.coinflow.process.store.Ohlc1mAggregationStore;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class Ohlc1mFlushScheduler {

    private static final ZoneOffset UTC = ZoneOffset.UTC;

    private final Ohlc1mAggregationStore store;
    private final Ohlc1mFlushService flushService;

    @Scheduled(fixedDelay = 1000)
    public void flushClosedBuckets() {
        LocalDateTime nowUtc = LocalDateTime.ofInstant(Instant.now(), UTC);

        for (AggregateKey key : store.keysSnapshot()) {
            if (!isClosed(nowUtc, key.bucket())) {
                continue;
            }

            OhlcAccumulator acc = store.peek(key);
            if (acc == null) {
                continue;
            }

            try {
                flushService.flush(key, acc);
                store.remove(key);
            } catch (Exception e) {
                log.error("Failed to flush bucket: symbol={}, bucket={}",
                        key.symbolId(), key.bucket(), e);
            }
        }
    }

    private boolean isClosed(LocalDateTime nowUtc, LocalDateTime bucketStart) {
        return !nowUtc.isBefore(bucketStart.plusMinutes(1));
    }
}
