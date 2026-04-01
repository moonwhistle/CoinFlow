package com.coinflow.chart.service;

import com.coinflow.chart.constant.ChartCacheConstants;
import com.coinflow.chart.repository.RedisOhlcWindowRepository;
import com.coinflow.domain.ohlc.cache.OhlcChartStore;
import com.coinflow.domain.ohlc.constant.OhlcInterval;
import com.coinflow.domain.ohlc.domain.AbstractOhlc;
import com.coinflow.domain.ohlc.repository.LiveKlineRepository;
import com.coinflow.domain.ohlc.service.Ohlc1mService;
import com.coinflow.domain.ohlc.service.Ohlc30mService;
import com.coinflow.domain.ohlc.service.Ohlc5mService;
import com.coinflow.domain.ohlc.snapshot.OhlcCandleSnapshot;
import com.coinflow.domain.symbol.domain.Symbol;
import com.coinflow.domain.symbol.service.SymbolService;
import com.coinflow.event.kline.KlineEvent;
import com.coinflow.util.TimeBucket;
import java.time.Clock;
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
 * High-performance chart service using a tiered caching strategy.
 * L1 (Caffeine - History) -> L1.5 (Redis ZSET - Hot Window) -> DB (Cold Storage).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OhlcChartService {

    private final OhlcChartStore chartStore; // Caffeine L2 (History)
    private final RedisOhlcWindowRepository ohlcWindowRepository; // Global L1.5 Window
    private final Clock clock;
    private final Ohlc1mService ohlc1mService;
    private final Ohlc5mService ohlc5mService;
    private final Ohlc30mService ohlc30mService;
    private final Optional<LiveKlineRepository> liveKlineRepository;
    private final SymbolService symbolService;

    private final ConcurrentHashMap<String, ReentrantLock> windowLocks = new ConcurrentHashMap<>();

    /**
     * Retrieves OHLC candles with high performance.
     * Uses Redis-first strategy for the last 1000 candles with DB backfill.
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
        boolean isHotPath = (currentEpoch - endEpoch) <= interval.duration().getSeconds() * ChartCacheConstants.MAX_HOT_WINDOW_SIZE;

        List<OhlcCandleSnapshot> finalizedCandles;

        if (isHotPath) {
            // Hot Path: Try Redis ZSET
            finalizedCandles = ohlcWindowRepository.findRange(symbol.getSymbol(), interval.name(), endEpoch, candles);
            
            // Thundering Herd Prevention: If Redis is empty, acquire lock and backfill
            if (finalizedCandles.size() < candles) {
                String lockKey = ChartCacheConstants.LOCK_KEY_PREFIX + symbol.getSymbol() + ":" + interval.name();
                ReentrantLock lock = windowLocks.computeIfAbsent(lockKey, k -> new ReentrantLock());
                
                try {
                    // Wait for the lock to prevent massive concurrent DB hits
                    if (lock.tryLock(ChartCacheConstants.LOCK_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                        try {
                            // Double-Check: Has another thread already filled the cache?
                            finalizedCandles = ohlcWindowRepository.findRange(symbol.getSymbol(), interval.name(), endEpoch, candles);
                            if (finalizedCandles.size() < candles) {
                                log.debug("[CHART-SERVICE] Redis miss/gap for {} {}. Backfilling...", symbol.getSymbol(), interval);
                                finalizedCandles = backfillAndLoad(symbol, interval, candles, endExclusive);
                            }
                        } finally {
                            lock.unlock();
                        }
                    } else {
                        log.warn("[CHART-SERVICE] Lock timeout for {}. Proceeding with potential concurrent backfill.", lockKey);
                        finalizedCandles = backfillAndLoad(symbol, interval, candles, endExclusive);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException("Interrupted while waiting for chart lock", e);
                }
            }
        } else {
            // Cold Path: Traditional DB + Caffeine L2 Cache (History)
            finalizedCandles = chartStore.getOrLoad(
                    symbolId, interval, candles, endExclusive,
                    () -> loadFromDb(symbolId, interval, candles, endExclusive)
            );
        }

        // Live Merge: Add the currently updating candle if it's a 'live' request
        if (to == null) {
            return mergeRealTimeCandleIntoSnapshot(finalizedCandles, symbol, base1mBucket, interval);
        }

        return finalizedCandles;
    }

    /**
     * Loads candles from DB and hydrates the Redis Global Window for future requests.
     */
    private List<OhlcCandleSnapshot> backfillAndLoad(Symbol symbol, OhlcInterval interval, int count, LocalDateTime endExclusive) {
        // Backfill a larger portion (MAX_HOT_WINDOW_SIZE) to prevent frequent misses
        List<OhlcCandleSnapshot> hotWindowData = loadFromDb(symbol.getId(), interval, ChartCacheConstants.MAX_HOT_WINDOW_SIZE, endExclusive);
        
        if (!hotWindowData.isEmpty()) {
            ohlcWindowRepository.saveAll(symbol.getSymbol(), interval.name(), hotWindowData);
            ohlcWindowRepository.trim(symbol.getSymbol(), interval.name(), ChartCacheConstants.MAX_HOT_WINDOW_SIZE);
        }

        // Return only the requested amount
        int size = hotWindowData.size();
        return hotWindowData.subList(Math.max(0, size - count), size);
    }

    private List<OhlcCandleSnapshot> loadFromDb(Long symbolId, OhlcInterval interval, int candles, LocalDateTime endExclusive) {
        LocalDateTime startInclusive = endExclusive.minus(interval.duration().multipliedBy(candles));

        return switch (interval) {
            case M1 -> toSnapshots(ohlc1mService.findCandlesInBucketRange(symbolId, startInclusive, endExclusive));
            case M5 -> toSnapshots(ohlc5mService.findCandlesInBucketRange(symbolId, startInclusive, endExclusive));
            case M30 -> toSnapshots(ohlc30mService.findCandlesInBucketRange(symbolId, startInclusive, endExclusive));
        };
    }

    private List<OhlcCandleSnapshot> toSnapshots(List<? extends AbstractOhlc> candles) {
        return candles.stream()
                .map(OhlcCandleSnapshot::from)
                .toList();
    }

    private List<OhlcCandleSnapshot> mergeRealTimeCandleIntoSnapshot(
            List<OhlcCandleSnapshot> snapshots, Symbol symbol,
            LocalDateTime baseBucket, OhlcInterval interval) {

        if (liveKlineRepository.isEmpty()) {
            return snapshots;
        }

        Optional<KlineEvent> liveKlineOpt = liveKlineRepository.get().findBySymbolAndInterval(
                symbol.getSymbol(), interval.name());

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
}

