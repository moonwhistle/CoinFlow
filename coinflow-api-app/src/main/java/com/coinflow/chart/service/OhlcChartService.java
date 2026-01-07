package com.coinflow.chart.service;

import com.coinflow.domain.ohlc.cache.OhlcChartStore;
import com.coinflow.domain.ohlc.constant.OhlcInterval;
import com.coinflow.domain.ohlc.snapshot.OhlcCandleSnapshot;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class OhlcChartService {

    private final OhlcChartStore chartStore;
    private final Clock clock;

    public List<OhlcCandleSnapshot> show(Long symbolId, OhlcInterval interval, int candles) {
        LocalDateTime endExclusive = interval.resolveBucketStart(LocalDateTime.now(clock));

        return chartStore.get(symbolId, interval, candles, endExclusive)
                .orElseGet(() -> loadAndCache(
                        symbolId,
                        interval,
                        candles,
                        endExclusive
                ));
    }

    private List<OhlcCandleSnapshot> loadAndCache(Long symbolId, OhlcInterval interval, int candles, LocalDateTime endExclusive) {
        List<OhlcCandleSnapshot> result = loadFromDataSource(symbolId, interval, candles, endExclusive);
        chartStore.put(symbolId, interval, candles, endExclusive, result);

        return result;
    }

    private List<OhlcCandleSnapshot> loadFromDataSource(Long symbolId, OhlcInterval interval, int candles, LocalDateTime endExclusive) {
        // TODO
    }
}
