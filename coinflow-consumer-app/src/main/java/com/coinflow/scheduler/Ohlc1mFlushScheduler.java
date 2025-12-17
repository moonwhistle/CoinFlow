package com.coinflow.scheduler;

import com.coinflow.process.aggregate.AggregateKey;
import com.coinflow.process.aggregate.Ohlc1mAccumulator;
import com.coinflow.process.service.Ohlc1mFlushService;
import com.coinflow.process.store.Ohlc1mAggregationStore;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class Ohlc1mFlushScheduler {

    private static final ZoneOffset UTC = ZoneOffset.UTC;

    private final Ohlc1mAggregationStore store;
    private final Ohlc1mFlushService flushService;

    /**
     * 1초 주기로 닫힌 1분 봉 flush
     */
    @Scheduled(fixedDelay = 1000)
    public void flushClosedBuckets() {
        LocalDateTime nowUtc = LocalDateTime.ofInstant(Instant.now(), UTC);

        for (AggregateKey key : store.keys()) {
            if (!isClosed(nowUtc, key.bucket())) {
                continue;
            }

            Ohlc1mAccumulator acc = store.remove(key);

            if (acc == null) {
                continue;
            }

            flushService.flush(key, acc);
        }
    }

    private boolean isClosed(LocalDateTime nowUtc, LocalDateTime bucketStart) {
        return nowUtc.isAfter(bucketStart.plusMinutes(1));
    }
}
