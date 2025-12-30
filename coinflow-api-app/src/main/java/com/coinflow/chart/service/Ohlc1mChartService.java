package com.coinflow.chart.service;

import com.coinflow.domain.ohlc.cache.OhlcChartStore;
import com.coinflow.domain.ohlc.constant.OhlcInterval;
import com.coinflow.domain.ohlc.snapshot.OhlcCandleSnapshot;
import com.coinflow.util.TimeBucket;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class Ohlc1mChartService {

    private static final OhlcInterval INTERVAL = OhlcInterval.M1;

    private final Clock clock;
    private final OhlcChartStore chartStore;


    public List<OhlcCandleSnapshot> show(Long symbolId) {
        LocalDateTime endExclusive = TimeBucket.to1m(clock.instant());

        return chartStore.loadRecent(symbolId, INTERVAL, endExclusive);
    }
}
