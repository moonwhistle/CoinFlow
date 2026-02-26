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
import com.coinflow.domain.ohlc.policy.VolumeScaler;
import java.time.ZoneOffset;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
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
        Instant nowInstant = Instant.now(clock);
        LocalDateTime base1mBucket = TimeBucket.to1m(nowInstant);
        LocalDateTime endExclusive = interval.resolveBucketStart(base1mBucket);

        return chartStore.get(symbolId, interval, candles, endExclusive)
                .orElseGet(() -> loadAndCache(
                        symbolId,
                        interval,
                        candles,
                        endExclusive,
                        base1mBucket));
    }

    private List<OhlcCandleSnapshot> loadAndCache(Long symbolId, OhlcInterval interval, int candles,
            LocalDateTime endExclusive, LocalDateTime base1mBucket) {
        List<OhlcCandleSnapshot> result = loadFromDataSource(symbolId, interval, candles, endExclusive, base1mBucket);
        chartStore.put(symbolId, interval, candles, endExclusive, result);

        return result;
    }

    private List<OhlcCandleSnapshot> loadFromDataSource(Long symbolId, OhlcInterval interval, int candles,
            LocalDateTime endExclusive, LocalDateTime base1mBucket) {
        LocalDateTime startInclusive = endExclusive.minus(interval.duration().multipliedBy(candles));

        if (interval == OhlcInterval.M1) {
            List<Ohlc1m> candles1m = ohlc1mService.findCandlesInBucketRange(symbolId, startInclusive, endExclusive);
            candles1m = mergeRealTimeCandle(candles1m, symbolId, endExclusive);
            return candles1m.stream()
                    .map(OhlcCandleSnapshot::from)
                    .toList();
        }

        if (interval == OhlcInterval.M5) {
            List<OhlcCandleSnapshot> snapshots = ohlc5mService
                    .findCandlesInBucketRange(symbolId, startInclusive, endExclusive)
                    .stream()
                    .map(OhlcCandleSnapshot::from)
                    .toList();
            return mergeRealTimeCandleIntoSnapshot(snapshots, symbolId, base1mBucket, interval);
        }

        if (interval == OhlcInterval.M30) {
            List<OhlcCandleSnapshot> snapshots = ohlc30mService
                    .findCandlesInBucketRange(symbolId, startInclusive, endExclusive)
                    .stream()
                    .map(OhlcCandleSnapshot::from)
                    .toList();
            return mergeRealTimeCandleIntoSnapshot(snapshots, symbolId, base1mBucket, interval);
        }

        throw new ApiException(ApiErrorCode.UNSUPPORTED_OHLC_INTERVAL);
    }

    /**
     * M1 전용: DB 캔들 리스트에 실시간 M1 캔들(Redis 스냅샷 + Stream Replay)을 병합한다.
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

    /**
     * M5/M30 전용: M1 라이브 캔들을 가져와서 해당하는 M5/M30 버킷에 OHLCV를 합산한다.
     *
     * 예) 12:03에 M5 차트 조회 시:
     * - DB의 M5 버킷 {12:00}: 12:00~12:02의 M1 데이터가 이미 롤업됨
     * - Redis M1 라이브: 12:03 캔들 (진행 중)
     * - 결과: M5 {12:00} 버킷에 12:03 M1 데이터를 합산 → high/low 확장, close 갱신, volume 누적
     */
    private List<OhlcCandleSnapshot> mergeRealTimeCandleIntoSnapshot(
            List<OhlcCandleSnapshot> snapshots, Long symbolId,
            LocalDateTime base1mBucket, OhlcInterval interval) {
        if (realTimeOhlcProvider.isEmpty()) {
            return snapshots;
        }

        // 현재 진행 중인 M1 버킷의 라이브 캔들 조회
        LocalDateTime currentM1Bucket = base1mBucket.minusMinutes(1);
        Optional<Ohlc1m> realTimeCandleOpt = realTimeOhlcProvider.get().getRealTimeCandle(symbolId, currentM1Bucket);
        if (realTimeCandleOpt.isEmpty()) {
            return snapshots;
        }

        Ohlc1m liveM1 = realTimeCandleOpt.get();

        // 이 M1 캔들이 속하는 M5/M30 버킷 시간 계산
        LocalDateTime parentBucketTime = interval.resolveBucketStart(liveM1.getBucketTime());
        log.debug("Merging M1 live candle (bucketTime={}) into {} bucket (bucketTime={})",
                liveM1.getBucketTime(), interval, parentBucketTime);

        List<OhlcCandleSnapshot> result = new ArrayList<>(snapshots);

        BigDecimal liveOpen = liveM1.getOpenPrice();
        BigDecimal liveHigh = liveM1.getHighPrice();
        BigDecimal liveLow = liveM1.getLowPrice();
        BigDecimal liveClose = liveM1.getClosePrice();
        BigDecimal liveVolume = VolumeScaler.toBigDecimal(liveM1.getVolume());

        // 해당 M5/M30 버킷을 찾아서 OHLCV 합산
        boolean merged = false;
        for (int i = 0; i < result.size(); i++) {
            OhlcCandleSnapshot existing = result.get(i);
            if (existing.bucketTime().equals(parentBucketTime)) {
                result.set(i, new OhlcCandleSnapshot(
                        existing.bucketTime(),
                        existing.bucketTime().toEpochSecond(ZoneOffset.UTC),
                        existing.openPrice(), // Open은 기존 유지
                        existing.highPrice().max(liveHigh), // High 확장
                        existing.lowPrice().min(liveLow), // Low 확장
                        liveClose, // Close는 최신으로 갱신
                        existing.volume().add(liveVolume) // Volume 누적
                ));
                merged = true;
                break;
            }
        }

        // 해당 버킷이 DB에 아직 없는 경우 (예: 새 M5 버킷의 첫 틱)
        if (!merged) {
            result.add(new OhlcCandleSnapshot(
                    parentBucketTime,
                    parentBucketTime.toEpochSecond(ZoneOffset.UTC),
                    liveOpen, liveHigh, liveLow, liveClose, liveVolume));
        }

        return result;
    }
}
