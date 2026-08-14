package com.coinflow.chart.cache.hot;

import com.coinflow.domain.ohlc.constant.OhlcWindowPolicy;
import com.coinflow.domain.ohlc.snapshot.OhlcCandleSnapshot;
import com.coinflow.event.kline.KlineEvent;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.cache.CaffeineCacheMetrics;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.stereotype.Component;

@Component
public class OhlcHotWindowStore {

    private static final int MAX_CACHE_KEYS = 500;

    private final Cache<String, OhlcHotWindow> cache;

    public OhlcHotWindowStore(MeterRegistry meterRegistry) {
        cache = Caffeine.newBuilder()
                .maximumSize(MAX_CACHE_KEYS)
                .expireAfterAccess(10, TimeUnit.MINUTES)
                .recordStats()
                .build();
        CaffeineCacheMetrics.monitor(meterRegistry, cache, "ohlc_hot_window_cache");
    }

    public Optional<OhlcHotWindow> get(String symbol, String interval) {
        return Optional.ofNullable(cache.getIfPresent(key(symbol, interval)));
    }

    public void replace(
            String symbol,
            String interval,
            List<OhlcCandleSnapshot> finalizedCandles,
            Optional<KlineEvent> liveCandle,
            Instant synchronizedAt
    ) {
        KlineEvent openLiveCandle = liveCandle.filter(event -> !event.closed()).orElse(null);
        cache.put(
                key(symbol, interval),
                new OhlcHotWindow(normalize(finalizedCandles), openLiveCandle, synchronizedAt, 0)
        );
    }

    public long eventVersion(String symbol, String interval) {
        OhlcHotWindow current = cache.getIfPresent(key(symbol, interval));
        return current == null ? 0 : current.eventVersion();
    }

    public boolean replaceIfVersion(
            String symbol,
            String interval,
            List<OhlcCandleSnapshot> finalizedCandles,
            Optional<KlineEvent> liveCandle,
            Instant synchronizedAt,
            long expectedVersion
    ) {
        String key = key(symbol, interval);
        KlineEvent openLiveCandle = liveCandle.filter(event -> !event.closed()).orElse(null);
        AtomicBoolean replaced = new AtomicBoolean();
        cache.asMap().compute(key, (ignored, current) -> {
            long currentVersion = current == null ? 0 : current.eventVersion();
            if (currentVersion != expectedVersion) {
                return current;
            }
            replaced.set(true);
            return new OhlcHotWindow(
                    normalize(finalizedCandles), openLiveCandle, synchronizedAt, currentVersion);
        });
        return replaced.get();
    }

    public void applyEvent(KlineEvent event) {
        String key = key(event.symbol(), event.interval());
        cache.asMap().compute(key, (ignored, current) -> {
            OhlcHotWindow base = current != null
                    ? current
                    : new OhlcHotWindow(List.of(), null, Instant.EPOCH, 0);

            if (!event.closed()) {
                return new OhlcHotWindow(
                        base.finalizedCandles(), event, base.synchronizedAt(), base.eventVersion() + 1);
            }

            List<OhlcCandleSnapshot> updated = new ArrayList<>(base.finalizedCandles());
            updated.removeIf(candle -> candle.epochSeconds() == event.startTime());
            updated.add(toSnapshot(event));
            KlineEvent live = base.liveCandleOptional()
                    .filter(candidate -> candidate.startTime() != event.startTime())
                    .orElse(null);
            return new OhlcHotWindow(
                    normalize(updated), live, base.synchronizedAt(), base.eventVersion() + 1);
        });
    }

    private List<OhlcCandleSnapshot> normalize(List<OhlcCandleSnapshot> candles) {
        Map<Long, OhlcCandleSnapshot> byTimestamp = new LinkedHashMap<>();
        candles.stream()
                .sorted(Comparator.comparingLong(OhlcCandleSnapshot::epochSeconds))
                .forEach(candle -> byTimestamp.put(candle.epochSeconds(), candle));

        List<OhlcCandleSnapshot> normalized = new ArrayList<>(byTimestamp.values());
        int fromIndex = Math.max(0, normalized.size() - OhlcWindowPolicy.MAX_SIZE);
        return List.copyOf(normalized.subList(fromIndex, normalized.size()));
    }

    private OhlcCandleSnapshot toSnapshot(KlineEvent event) {
        LocalDateTime bucketTime = LocalDateTime.ofEpochSecond(
                event.startTime(), 0, ZoneOffset.UTC);
        return new OhlcCandleSnapshot(
                bucketTime,
                event.startTime(),
                event.open(),
                event.high(),
                event.low(),
                event.close(),
                event.volume()
        );
    }

    private String key(String symbol, String interval) {
        return symbol.toLowerCase() + ":" + interval;
    }
}
