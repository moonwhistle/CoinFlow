package com.coinflow.chart.service;

import com.coinflow.common.exception.ApiErrorCode;
import com.coinflow.common.exception.ApiException;
import com.coinflow.domain.ohlc.cache.OhlcChartStore;
import com.coinflow.domain.ohlc.constant.OhlcInterval;
import com.coinflow.domain.ohlc.service.Ohlc1mService;
import com.coinflow.domain.ohlc.service.Ohlc30mService;
import com.coinflow.domain.ohlc.service.Ohlc5mService;
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
    private final Ohlc1mService ohlc1mService;
    private final Ohlc5mService ohlc5mService;
    private final Ohlc30mService ohlc30mService;

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

    private List<OhlcCandleSnapshot> loadAndCache(Long symbolId, OhlcInterval interval, int candles,
                                                  LocalDateTime endExclusive) {
        List<OhlcCandleSnapshot> result = loadFromDataSource(symbolId, interval, candles, endExclusive);
        chartStore.put(symbolId, interval, candles, endExclusive, result);

        return result;
    }

    private List<OhlcCandleSnapshot> loadFromDataSource(Long symbolId, OhlcInterval interval, int candles,
                                                        LocalDateTime endExclusive) {
        LocalDateTime startInclusive = endExclusive.minus(interval.duration().multipliedBy(candles));

        if (interval == OhlcInterval.M1) {
            return ohlc1mService.findCandlesInBucketRange(symbolId, startInclusive, endExclusive)
                    .stream()
                    .map(OhlcCandleSnapshot::from)
                    .toList();
        }

        if (interval == OhlcInterval.M5) {
            return ohlc5mService.findCandlesInBucketRange(symbolId, startInclusive, endExclusive)
                    .stream()
                    .map(OhlcCandleSnapshot::from)
                    .toList();
        }

        if (interval == OhlcInterval.M30) {
            return ohlc30mService.findCandlesInBucketRange(symbolId, startInclusive, endExclusive)
                    .stream()
                    .map(OhlcCandleSnapshot::from)
                    .toList();
        }

        throw new ApiException(ApiErrorCode.UNSUPPORTED_OHLC_INTERVAL);
    }
}
