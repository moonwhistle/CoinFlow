package com.coinflow.chart.service;

import com.coinflow.domain.ohlc.cache.OhlcChartStore;
import com.coinflow.domain.ohlc.constant.OhlcInterval;
import com.coinflow.domain.ohlc.domain.Ohlc1m;
import com.coinflow.domain.ohlc.service.Ohlc1mService;
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
    private static final int LIMIT = 120;

    private final Clock clock;
    private final OhlcChartStore chartStore;
    private final Ohlc1mService ohlc1mService;


    public List<OhlcCandleSnapshot> show(Long symbolId) {
        LocalDateTime endExclusive = TimeBucket.to1m(clock.instant());

        return chartStore.get(symbolId, INTERVAL, endExclusive)
                .orElseGet(() -> loadFromDbAndCache(symbolId, endExclusive));
    }

    private List<OhlcCandleSnapshot> loadFromDbAndCache(Long symbolId, LocalDateTime endExclusive) {
        List<OhlcCandleSnapshot> candles = buildSnapshot(symbolId, endExclusive);
        chartStore.put(symbolId, INTERVAL, endExclusive, candles);

        return candles;
    }

    // DB 단에서 정렬 완료
    private List<OhlcCandleSnapshot> buildSnapshot(Long symbolId, LocalDateTime endExclusive) {
        LocalDateTime startInclusive = endExclusive.minusMinutes(LIMIT);
        List<Ohlc1m> rows = ohlc1mService.findCandlesInBucketRange(symbolId, startInclusive, endExclusive);

        return rows.stream()
                .map(this::toSnapshot)
                .toList();
    }

    private OhlcCandleSnapshot toSnapshot(Ohlc1m candle) {
        return new OhlcCandleSnapshot(
                candle.getBucketTime(),
                candle.getOpenPrice(),
                candle.getHighPrice(),
                candle.getLowPrice(),
                candle.getClosePrice(),
                candle.getVolume()
        );
    }
}
