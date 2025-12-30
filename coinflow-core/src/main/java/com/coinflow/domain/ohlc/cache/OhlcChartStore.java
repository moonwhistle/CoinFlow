package com.coinflow.domain.ohlc.cache;

import com.coinflow.domain.ohlc.constant.OhlcInterval;
import com.coinflow.domain.ohlc.snapshot.OhlcCandleSnapshot;
import java.time.LocalDateTime;
import java.util.List;

public interface OhlcChartStore {

    List<OhlcCandleSnapshot> loadRecent(
            Long symbolId,
            OhlcInterval interval,
            LocalDateTime endExclusive
    );
}
