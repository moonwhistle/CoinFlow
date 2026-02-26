package com.coinflow.domain.ohlc.repository;

import com.coinflow.domain.ohlc.constant.OhlcInterval;
import com.coinflow.domain.ohlc.snapshot.LiveCandleSnapshot;

import java.util.Optional;

public interface OhlcLiveSnapshotRepository {
    void save(Long symbolId, OhlcInterval interval, LiveCandleSnapshot snapshot);

    Optional<LiveCandleSnapshot> find(Long symbolId, OhlcInterval interval);
}
