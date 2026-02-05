package com.coinflow.domain.ohlc.store;

import com.coinflow.domain.ohlc.constant.OhlcInterval;
import com.coinflow.domain.ohlc.snapshot.OhlcCandleSnapshot;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class LocalOhlcStore {

    // Map<SymbolId, Map<OhlcInterval, OhlcCandleSnapshot>>
    private final Map<Long, Map<OhlcInterval, OhlcCandleSnapshot>> store = new ConcurrentHashMap<>();

    public void update(Long symbolId, LocalDateTime bucketTime, OhlcInterval interval,
            BigDecimal price, long volume) {

        store.compute(symbolId, (key, intervalMap) -> {
            if (intervalMap == null) {
                intervalMap = new ConcurrentHashMap<>();
            }

            intervalMap.compute(interval, (k, existing) -> {
                // If existing bucket is old (bucketTime > existing.bucketTime), we should have
                // flushed it.
                // For now, we assume strict ordering or just overwrite/new bucket.
                // Assuming TimeBucket validation happens before calling this.

                if (existing == null || !existing.bucketTime().equals(bucketTime)) {
                    // New bucket started
                    // TODO: Move old bucket to "Closed" queue? (For Phase 3)
                    return new OhlcCandleSnapshot(
                            bucketTime, price, price, price, price, volume);
                }

                // Update existing
                BigDecimal high = existing.highPrice().max(price);
                BigDecimal low = existing.lowPrice().min(price);
                long newVolume = existing.volume() + volume;

                return new OhlcCandleSnapshot(
                        bucketTime,
                        existing.openPrice(),
                        high,
                        low,
                        price, // Close is current price
                        newVolume);
            });
            return intervalMap;
        });
    }

    public OhlcCandleSnapshot get(Long symbolId, OhlcInterval interval) {
        Map<OhlcInterval, OhlcCandleSnapshot> map = store.get(symbolId);
        if (map == null) {
            return null;
        }
        return map.get(interval);
    }

    public Map<Long, Map<OhlcInterval, OhlcCandleSnapshot>> getAll() {
        return Collections.unmodifiableMap(store);
    }
}
