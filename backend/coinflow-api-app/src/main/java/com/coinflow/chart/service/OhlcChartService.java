package com.coinflow.chart.service;

import com.coinflow.chart.cache.hot.OhlcHotWindow;
import com.coinflow.chart.cache.hot.OhlcHotWindowStore;
import com.coinflow.chart.constant.ChartCacheConstants;
import com.coinflow.domain.ohlc.cache.OhlcChartStore;
import com.coinflow.domain.ohlc.constant.OhlcInterval;
import com.coinflow.domain.ohlc.constant.OhlcWindowPolicy;
import com.coinflow.domain.ohlc.domain.AbstractOhlc;
import com.coinflow.domain.ohlc.repository.LiveKlineRepository;
import com.coinflow.domain.ohlc.repository.OhlcWindowRepository;
import com.coinflow.domain.ohlc.service.Ohlc1mService;
import com.coinflow.domain.ohlc.service.Ohlc30mService;
import com.coinflow.domain.ohlc.service.Ohlc5mService;
import com.coinflow.domain.ohlc.snapshot.OhlcCandleSnapshot;
import com.coinflow.domain.symbol.domain.Symbol;
import com.coinflow.domain.symbol.service.SymbolService;
import com.coinflow.event.kline.KlineEvent;
import com.coinflow.util.TimeBucket;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Chart service using local hot/history caches, Redis global windows, and DB fallback.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OhlcChartService {

    private final OhlcChartStore chartStore;
    private final OhlcWindowRepository ohlcWindowRepository;
    private final OhlcHotWindowStore hotWindowStore;
    private final Clock clock;
    private final Ohlc1mService ohlc1mService;
    private final Ohlc5mService ohlc5mService;
    private final Ohlc30mService ohlc30mService;
    private final Optional<LiveKlineRepository> liveKlineRepository;
    private final SymbolService symbolService;

    private final ConcurrentHashMap<String, ReentrantLock> windowLocks = new ConcurrentHashMap<>();

    /**
     * Retrieves OHLC candles with high performance.
     * Uses the local hot window for the last 1000 candles with Redis and DB fallback.
     * Prevents Thundering Herd via local Mutex and Double-Checked Locking.
     */
    public List<OhlcCandleSnapshot> show(Long symbolId, OhlcInterval interval, int candles, LocalDateTime to) {
        Symbol symbol = symbolService.findSymbol(symbolId);

        // Calculate the target boundary (exclusive) for finalized candles
        Instant baseInstant = (to != null) ? to.toInstant(ZoneOffset.UTC) : clock.instant();
        LocalDateTime base1mBucket = TimeBucket.to1m(baseInstant);
        LocalDateTime endExclusive = interval.resolveBucketStart(base1mBucket);

        long endEpoch = endExclusive.toEpochSecond(ZoneOffset.UTC);

        // Check if the request is within the 'Hot Window' range (last 1000)
        long currentEpoch = TimeBucket.to1m(clock.instant()).toEpochSecond(ZoneOffset.UTC);
        boolean isHotPath = (currentEpoch - endEpoch)
                <= interval.duration().getSeconds() * OhlcWindowPolicy.MAX_SIZE;

        List<OhlcCandleSnapshot> finalizedCandles;
        Optional<KlineEvent> liveCandle = Optional.empty();

        if (isHotPath) {
            Optional<OhlcHotWindow> localWindow = hotWindowStore
                    .get(symbol.getSymbol(), interval.name())
                    .filter(this::isFresh);

            if (localWindow.isPresent()) {
                finalizedCandles = localWindow.get().findFinalizedRange(endEpoch, candles);
                liveCandle = localWindow.get().liveCandleOptional();
            } else {
                OhlcHotWindow redisWindow = loadRedisHotWindow(symbol, interval, endEpoch);
                finalizedCandles = redisWindow.findFinalizedRange(endEpoch, candles);
                liveCandle = redisWindow.liveCandleOptional();
            }
            
            if (finalizedCandles.size() < candles) {
                String lockKey = ChartCacheConstants.LOCK_KEY_PREFIX + symbol.getSymbol() + ":" + interval.name();
                ReentrantLock lock = windowLocks.computeIfAbsent(lockKey, k -> new ReentrantLock());
                
                try {
                    // Wait for the lock to prevent massive concurrent DB hits
                    if (lock.tryLock(ChartCacheConstants.LOCK_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                        try {
                            // Double-Check: Has another thread already filled the cache?
                            OhlcHotWindow redisWindow = loadRedisHotWindow(symbol, interval, endEpoch);
                            finalizedCandles = redisWindow.findFinalizedRange(endEpoch, candles);
                            liveCandle = redisWindow.liveCandleOptional();
                            if (finalizedCandles.size() < candles) {
                                log.debug(
                                        "[CHART-SERVICE] Redis miss/gap for {} {}. Backfilling...",
                                        symbol.getSymbol(), interval);
                                finalizedCandles = backfillAndLoad(
                                        symbol, interval, candles, endExclusive);
                                OhlcHotWindow refreshedWindow = loadRedisHotWindow(symbol, interval, endEpoch);
                                liveCandle = refreshedWindow.liveCandleOptional();
                            }
                        } finally {
                            lock.unlock();
                        }
                    } else {
                        log.warn(
                                "[CHART-SERVICE] Lock timeout for {}. "
                                        + "Proceeding with potential concurrent backfill.",
                                lockKey);
                        finalizedCandles = backfillAndLoad(
                                symbol, interval, candles, endExclusive);
                        OhlcHotWindow refreshedWindow = loadRedisHotWindow(symbol, interval, endEpoch);
                        liveCandle = refreshedWindow.liveCandleOptional();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException("Interrupted while waiting for chart lock", e);
                }
            }
        } else {
            // Cold Path: Traditional DB + Caffeine L2 Cache (History)
            // Optimization: Snap the 'endExclusive' to the nearest bucket start to unify cache keys in history.
            // This prevents duplicate entries in Caffeine for slightly different cursors within the same minute.
            LocalDateTime snappedEndExclusive = interval.resolveBucketStart(endExclusive);
            finalizedCandles = chartStore.getOrLoad(
                    symbolId, interval, candles, snappedEndExclusive,
                    () -> loadFromDb(symbolId, interval, candles, snappedEndExclusive)
            );
        }

        // Live Merge: Add the currently updating candle if it's a 'live' request
        if (to == null) {
            return mergeRealTimeCandleIntoSnapshot(
                    finalizedCandles, symbol, endExclusive, interval, liveCandle);
        }

        return finalizedCandles;
    }

    /**
     * Warms up the Redis cache for a specific symbol and interval.
     * Used at startup to prevent Cold Start latency.
     */
    public void warmUp(Symbol symbol, OhlcInterval interval) {
        LocalDateTime nowBucket = TimeBucket.to1m(clock.instant());
        LocalDateTime endExclusive = interval.resolveBucketStart(nowBucket);
        
        log.info("[CHART-SERVICE] Warming up cache for {} {}", symbol.getSymbol(), interval);
        backfillAndLoad(symbol, interval, OhlcWindowPolicy.MAX_SIZE, endExclusive);
        loadRedisHotWindow(
                symbol,
                interval,
                endExclusive.toEpochSecond(ZoneOffset.UTC)
        );
    }

    /**
     * Loads candles from DB and hydrates the Redis Global Window for future requests.
     */
    private List<OhlcCandleSnapshot> backfillAndLoad(
            Symbol symbol,
            OhlcInterval interval,
            int count,
            LocalDateTime endExclusive
    ) {
        // Backfill a larger portion (MAX_HOT_WINDOW_SIZE) to prevent frequent misses
        List<OhlcCandleSnapshot> hotWindowData = loadFromDb(
                symbol.getId(), interval, OhlcWindowPolicy.MAX_SIZE, endExclusive);
        
        if (!hotWindowData.isEmpty()) {
            ohlcWindowRepository.saveAll(symbol.getSymbol(), interval.name(), hotWindowData);
            ohlcWindowRepository.trim(
                    symbol.getSymbol(), interval.name(), OhlcWindowPolicy.MAX_SIZE);
        }

        // Return only the requested amount
        int size = hotWindowData.size();
        return hotWindowData.subList(Math.max(0, size - count), size);
    }

    private List<OhlcCandleSnapshot> loadFromDb(
            Long symbolId,
            OhlcInterval interval,
            int candles,
            LocalDateTime endExclusive
    ) {
        LocalDateTime startInclusive = endExclusive.minus(interval.duration().multipliedBy(candles));

        return switch (interval) {
            case M1 -> toSnapshots(ohlc1mService.findCandlesInBucketRange(
                    symbolId, startInclusive, endExclusive));
            case M5 -> toSnapshots(ohlc5mService.findCandlesInBucketRange(
                    symbolId, startInclusive, endExclusive));
            case M30 -> toSnapshots(ohlc30mService.findCandlesInBucketRange(
                    symbolId, startInclusive, endExclusive));
        };
    }

    private List<OhlcCandleSnapshot> toSnapshots(List<? extends AbstractOhlc> candles) {
        return candles.stream()
                .map(OhlcCandleSnapshot::from)
                .toList();
    }

    private List<OhlcCandleSnapshot> mergeRealTimeCandleIntoSnapshot(
            List<OhlcCandleSnapshot> snapshots, Symbol symbol,
            LocalDateTime baseBucket, OhlcInterval interval,
            Optional<KlineEvent> cachedLiveCandle) {

        Optional<KlineEvent> liveKlineOpt = cachedLiveCandle;
        if (liveKlineOpt.isEmpty() && hotWindowStore.get(symbol.getSymbol(), interval.name()).isEmpty()
                && liveKlineRepository.isPresent()) {
            liveKlineOpt = liveKlineRepository.get().findBySymbolAndInterval(
                    symbol.getSymbol(), interval.name());
        }

        if (liveKlineOpt.isEmpty()) {
            return snapshots;
        }

        KlineEvent liveKline = liveKlineOpt.get();
        LocalDateTime liveBucketTime = LocalDateTime.ofEpochSecond(liveKline.startTime(), 0, ZoneOffset.UTC);

        if (!liveBucketTime.equals(baseBucket)) {
            return snapshots;
        }

        OhlcCandleSnapshot liveSnapshot = new OhlcCandleSnapshot(
                liveBucketTime,
                liveBucketTime.toEpochSecond(ZoneOffset.UTC),
                liveKline.open(),
                liveKline.high(),
                liveKline.low(),
                liveKline.close(),
                liveKline.volume());

        List<OhlcCandleSnapshot> result = new ArrayList<>(snapshots);

        boolean replaced = false;
        for (int i = 0; i < result.size(); i++) {
            if (result.get(i).bucketTime().equals(baseBucket)) {
                result.set(i, liveSnapshot);
                replaced = true;
                break;
            }
        }

        if (!replaced) {
            result.add(liveSnapshot);
        }

        return result;
    }

    private OhlcHotWindow loadRedisHotWindow(Symbol symbol, OhlcInterval interval, long endEpoch) {
        long expectedVersion = hotWindowStore.eventVersion(symbol.getSymbol(), interval.name());
        List<OhlcCandleSnapshot> finalized = ohlcWindowRepository.findRange(
                symbol.getSymbol(),
                interval.name(),
                endEpoch,
                OhlcWindowPolicy.MAX_SIZE
        );
        Optional<KlineEvent> live = liveKlineRepository.flatMap(repository ->
                repository.findBySymbolAndInterval(symbol.getSymbol(), interval.name()));
        hotWindowStore.replaceIfVersion(
                symbol.getSymbol(),
                interval.name(),
                finalized,
                live,
                Instant.now(clock),
                expectedVersion
        );
        return hotWindowStore.get(symbol.getSymbol(), interval.name())
                .orElseGet(() -> new OhlcHotWindow(finalized, live.orElse(null), Instant.now(clock), 0));
    }

    private boolean isFresh(OhlcHotWindow window) {
        if (Instant.EPOCH.equals(window.synchronizedAt())) {
            return false;
        }
        long ageMillis = Duration.between(window.synchronizedAt(), clock.instant()).toMillis();
        return ageMillis >= 0 && ageMillis <= ChartCacheConstants.HOT_WINDOW_STALE_AFTER_MILLIS;
    }
}

