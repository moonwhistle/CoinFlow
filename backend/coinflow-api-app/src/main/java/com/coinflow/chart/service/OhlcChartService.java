package com.coinflow.chart.service;

import com.coinflow.common.exception.ApiErrorCode;
import com.coinflow.common.exception.ApiException;
import com.coinflow.domain.ohlc.cache.OhlcChartStore;
import com.coinflow.domain.ohlc.constant.OhlcInterval;
import com.coinflow.domain.ohlc.domain.Ohlc1m;
import com.coinflow.domain.ohlc.provider.RealTimeOhlcProvider;
import com.coinflow.domain.ohlc.service.Ohlc1mService;
import com.coinflow.domain.ohlc.service.Ohlc30mService;
import com.coinflow.domain.ohlc.service.Ohlc5mService;
import com.coinflow.domain.ohlc.snapshot.OhlcCandleSnapshot;
import com.coinflow.util.TimeBucket;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class OhlcChartService {

    private final OhlcChartStore chartStore;
    private final Clock clock;
    private final Ohlc1mService ohlc1mService;
    private final Ohlc5mService ohlc5mService;
    private final Ohlc30mService ohlc30mService;
    private final Optional<RealTimeOhlcProvider> realTimeOhlcProvider;

    public List<OhlcCandleSnapshot> show(Long symbolId, OhlcInterval interval, int candles) {
        java.time.Instant nowInstant = java.time.Instant.now(clock);
        LocalDateTime base1mBucket = TimeBucket.to1m(nowInstant);
        LocalDateTime endExclusive = interval.resolveBucketStart(base1mBucket);

        return chartStore.get(symbolId, interval, candles, endExclusive)
                .orElseGet(() -> loadAndCache(
                        symbolId,
                        interval,
                        candles,
                        endExclusive));
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
            List<Ohlc1m> candles1m = ohlc1mService.findCandlesInBucketRange(symbolId, startInclusive, endExclusive);
            candles1m = mergeRealTimeCandle(candles1m, symbolId, endExclusive);
            return candles1m.stream()
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

    /**
     * DB에서 가져온 캔들 리스트에 실시간 캔들(Redis 스냅샷 + Stream Replay)을 병합한다.
     * 이미 DB에 해당 버킷이 있으면 교체, 없으면 추가한다.
     */
    private List<Ohlc1m> mergeRealTimeCandle(List<Ohlc1m> candles, Long symbolId, LocalDateTime endExclusive) {
        if (realTimeOhlcProvider.isEmpty()) {
            return candles;
        }

        LocalDateTime lastBucketTime = endExclusive.minusMinutes(1);
        log.debug("Requesting realTimeCandle for symbolId={}, lastBucketTime={}", symbolId, lastBucketTime);

        Optional<Ohlc1m> realTimeCandleOpt = realTimeOhlcProvider.get().getRealTimeCandle(symbolId, lastBucketTime);
        if (realTimeCandleOpt.isEmpty()) {
            log.debug("getRealTimeCandle returned Optional.empty for symbolId={}", symbolId);
            return candles;
        }

        Ohlc1m realTimeCandle = realTimeCandleOpt.get();
        log.debug("getRealTimeCandle returned a candle for bucketTime={}", lastBucketTime);

        // DB 리스트를 mutable하게 복사 (JPA 반환 리스트가 불변일 수 있음)
        List<Ohlc1m> result = new ArrayList<>(candles);

        boolean replaced = false;
        for (int i = 0; i < result.size(); i++) {
            if (result.get(i).getBucketTime().equals(lastBucketTime)) {
                result.set(i, realTimeCandle);
                replaced = true;
                break;
            }
        }
        if (!replaced) {
            result.add(realTimeCandle);
        }

        return result;
    }
}
