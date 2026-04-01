package com.coinflow.chart.repository;

import com.coinflow.domain.ohlc.snapshot.OhlcCandleSnapshot;
import java.util.List;

/**
 * Interface for managing the Redis Sorted Set (ZSET) based OHLC window.
 * Stores the last N closed candles for a given symbol and interval.
 */
public interface RedisOhlcWindowRepository {

    /**
     * Saves a candle snapshot to the Redis ZSET.
     * Uses bucket startTime (epoch seconds) as the score to ensure O(log N) updates.
     */
    void save(String symbol, String interval, OhlcCandleSnapshot snapshot);

    /**
     * Batch saves multiple candle snapshots to the Redis ZSET.
     * Useful for initial DB backfilling (Gap-fill).
     */
    void saveAll(String symbol, String interval, List<OhlcCandleSnapshot> snapshots);

    /**
     * Retrieves a range of candle snapshots from the Redis ZSET.
     * @param to The end timestamp (exclusive) for the query.
     * @param limit The number of candles to retrieve.
     */
    List<OhlcCandleSnapshot> findRange(String symbol, String interval, long to, int limit);

    /**
     * Trims the Redis ZSET to maintain only the last N items.
     * Usually called after saving a new candle.
     */
    void trim(String symbol, String interval, int limit);
}
