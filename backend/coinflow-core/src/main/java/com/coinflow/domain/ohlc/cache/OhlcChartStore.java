package com.coinflow.domain.ohlc.cache;

import com.coinflow.domain.ohlc.constant.OhlcInterval;
import com.coinflow.domain.ohlc.snapshot.OhlcCandleSnapshot;
import java.time.LocalDateTime;
import java.util.List;
import java.util.function.Supplier;

public interface OhlcChartStore {

    /**
     * 캐시에 데이터가 없으면 loader를 실행하여 캐시에 저장 후 반환한다.
     * 구현체가 동시성 제어를 지원하면, 동일 키에 대해 loader는 단 1회만 실행된다.
     */
    List<OhlcCandleSnapshot> getOrLoad(
            Long symbolId,
            OhlcInterval interval,
            int candles,
            LocalDateTime endExclusive,
            Supplier<List<OhlcCandleSnapshot>> loader
    );
}

