package com.coinflow.chart.cache.store;

import com.coinflow.chart.cache.ohlc.OhlcCacheKey;
import com.coinflow.domain.ohlc.cache.OhlcChartStore;
import com.coinflow.domain.ohlc.constant.OhlcInterval;
import com.coinflow.domain.ohlc.snapshot.OhlcCandleSnapshot;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class CaffeineOhlcChartStore implements OhlcChartStore {

    /** 마감된 데이터는 분 단위로 키가 바뀌므로 60초면 충분함 */
    private static final Duration TTL = Duration.ofSeconds(60);
    private static final long MAX_SIZE = 500;

    private final Cache<String, List<OhlcCandleSnapshot>> cache;

    public CaffeineOhlcChartStore() {
        this.cache = Caffeine.newBuilder()
                .expireAfterWrite(TTL)
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
        List<OhlcCandleSnapshot> cached = cache.getIfPresent(key);

        if (cached != null) {
            log.debug("L1 Cache Hit! [Key: {}]", key);
            return Optional.of(cached);
        }

        log.debug("L1 Cache Miss! [Key: {}]", key);
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
        cache.put(key, snapshots);
        log.debug("L1 Cache Put! [Key: {}]", key);
    }
}
