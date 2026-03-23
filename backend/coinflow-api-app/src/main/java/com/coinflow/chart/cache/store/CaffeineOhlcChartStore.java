package com.coinflow.chart.cache.store;

import com.coinflow.chart.cache.ohlc.OhlcCacheKey;
import com.coinflow.domain.ohlc.cache.OhlcChartStore;
import com.coinflow.domain.ohlc.constant.OhlcInterval;
import com.coinflow.domain.ohlc.snapshot.OhlcCandleSnapshot;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Expiry;
import java.time.LocalDateTime;
import java.util.List;
import java.util.function.Supplier;
import lombok.extern.slf4j.Slf4j;
import org.checkerframework.checker.index.qual.NonNegative;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class CaffeineOhlcChartStore implements OhlcChartStore {

    private static final long MAX_SIZE = 500;

    private final Cache<String, CachedChart> cache;

    public CaffeineOhlcChartStore() {
        this.cache = Caffeine.newBuilder()
                .expireAfter(new Expiry<String, CachedChart>() {
                    @Override
                    public long expireAfterCreate(String key, CachedChart value, long currentTime) {
                        return value.ttlNanos();
                    }

                    @Override
                    public long expireAfterUpdate(String key, CachedChart value,
                            long currentTime, @NonNegative long currentDuration) {
                        return value.ttlNanos();
                    }

                    @Override
                    public long expireAfterRead(String key, CachedChart value,
                            long currentTime, @NonNegative long currentDuration) {
                        return currentDuration;
                    }
                })
                .maximumSize(MAX_SIZE)
                .build();
    }

    @Override
    public List<OhlcCandleSnapshot> getOrLoad(
            Long symbolId,
            OhlcInterval interval,
            int candles,
            LocalDateTime endExclusive,
            Supplier<List<OhlcCandleSnapshot>> loader
    ) {
        String key = OhlcCacheKey.chartKey(symbolId, interval, candles, endExclusive);

        CachedChart cached = cache.get(key, k -> {
            log.debug("L1 Cache Miss → Loading from DB [key={}, interval={}]", k, interval);
            List<OhlcCandleSnapshot> result = loader.get();
            return new CachedChart(result, interval.cacheTtl().toNanos());
        });

        log.debug("L1 Cache Hit [key={}, interval={}]", key, interval);
        return cached.snapshots();
    }

    private record CachedChart(List<OhlcCandleSnapshot> snapshots, long ttlNanos) {
    }
}
