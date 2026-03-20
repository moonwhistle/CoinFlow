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
import java.util.Optional;
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
    public Optional<List<OhlcCandleSnapshot>> get(
            Long symbolId,
            OhlcInterval interval,
            int candles,
            LocalDateTime endExclusive
    ) {
        String key = OhlcCacheKey.chartKey(symbolId, interval, candles, endExclusive);
        CachedChart cached = cache.getIfPresent(key);

        if (cached != null) {
            log.debug("L1 Cache Hit [key={}, interval={}]", key, interval);
            return Optional.of(cached.snapshots());
        }

        log.debug("L1 Cache Miss [key={}, interval={}]", key, interval);
        return Optional.empty();
    }

    @Override
    public void put(
            Long symbolId,
            OhlcInterval interval,
            int candles,
            LocalDateTime endExclusive,
            List<OhlcCandleSnapshot> snapshots
    ) {
        String key = OhlcCacheKey.chartKey(symbolId, interval, candles, endExclusive);
        cache.put(key, new CachedChart(snapshots, interval.cacheTtl().toNanos()));
        log.debug("L1 Cache Put [key={}, interval={}, ttl={}s]", key, interval, interval.cacheTtl().toSeconds());
    }

    private record CachedChart(List<OhlcCandleSnapshot> snapshots, long ttlNanos) {
    }
}
