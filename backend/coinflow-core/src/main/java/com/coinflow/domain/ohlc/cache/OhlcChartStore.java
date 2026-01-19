package com.coinflow.domain.ohlc.cache;

import com.coinflow.domain.ohlc.constant.OhlcInterval;
import com.coinflow.domain.ohlc.snapshot.OhlcCandleSnapshot;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface OhlcChartStore {

    Optional<List<OhlcCandleSnapshot>> get(
            Long symbolId,
            OhlcInterval interval,
            int candles,
            LocalDateTime endExclusive
    );

    void put(
            Long symbolId,
            OhlcInterval interval,
            int candles,
            LocalDateTime endExclusive,
            List<OhlcCandleSnapshot> snapshots
    );
}
