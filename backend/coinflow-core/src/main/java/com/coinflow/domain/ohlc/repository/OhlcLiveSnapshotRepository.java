package com.coinflow.domain.ohlc.repository;

import com.coinflow.domain.ohlc.constant.OhlcInterval;
import com.coinflow.domain.ohlc.domain.Ohlc1m;

import java.util.Optional;

public interface OhlcLiveSnapshotRepository {
    void save(Long symbolId, OhlcInterval interval, Ohlc1m ohlc1m);

    Optional<Ohlc1m> find(Long symbolId, OhlcInterval interval);
}
