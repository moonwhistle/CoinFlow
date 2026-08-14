package com.coinflow.domain.ohlc.repository;

import com.coinflow.domain.ohlc.snapshot.OhlcCandleSnapshot;
import java.util.List;

/**
 * Shared finalized-candle window backed by Redis.
 */
public interface OhlcWindowRepository {

    void save(String symbol, String interval, OhlcCandleSnapshot snapshot);

    void saveAll(String symbol, String interval, List<OhlcCandleSnapshot> snapshots);

    List<OhlcCandleSnapshot> findRange(String symbol, String interval, long to, int limit);

    void trim(String symbol, String interval, int limit);
}
