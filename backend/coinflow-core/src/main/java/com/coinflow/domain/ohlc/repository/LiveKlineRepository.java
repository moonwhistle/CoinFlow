package com.coinflow.domain.ohlc.repository;

import com.coinflow.event.kline.KlineEvent;
import java.util.Optional;

public interface LiveKlineRepository {

    /**
     * Set the current live kline state (JSON) in Redis.
     * Overwrites any existing value.
     */
    void save(KlineEvent klineEvent);

    /**
     * Get the current live kline state from Redis.
     * Used mainly by api-app to merge with DB data for initial load.
     */
    Optional<KlineEvent> findBySymbolAndInterval(String symbol, String interval);

    /**
     * Delete the live kline state from Redis.
     * Typically called when a candle is fully closed.
     */
    void delete(String symbol, String interval);

}
