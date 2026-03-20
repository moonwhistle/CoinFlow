package com.coinflow.chart.service;

import com.coinflow.common.exception.ApiErrorCode;
import com.coinflow.common.exception.ApiException;
import com.coinflow.domain.ohlc.cache.OhlcChartStore;
import com.coinflow.domain.ohlc.constant.OhlcInterval;
import com.coinflow.domain.ohlc.domain.Ohlc1m;
import com.coinflow.domain.ohlc.repository.LiveKlineRepository;
import com.coinflow.domain.ohlc.service.Ohlc1mService;
import com.coinflow.domain.ohlc.service.Ohlc30mService;
import com.coinflow.domain.ohlc.service.Ohlc5mService;
import com.coinflow.domain.ohlc.domain.AbstractOhlc;
import com.coinflow.domain.ohlc.snapshot.OhlcCandleSnapshot;
import com.coinflow.domain.symbol.domain.Symbol;
import com.coinflow.domain.symbol.service.SymbolService;
import com.coinflow.event.kline.KlineEvent;
import com.coinflow.util.TimeBucket;
import java.time.ZoneOffset;
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
    private final Optional<LiveKlineRepository> liveKlineRepository;
    private final SymbolService symbolService;

    public List<OhlcCandleSnapshot> show(Long symbolId, OhlcInterval interval, int candles) {
        Instant nowInstant = Instant.now(clock);
        LocalDateTime base1mBucket = TimeBucket.to1m(nowInstant);
        LocalDateTime endExclusive = interval.resolveBucketStart(base1mBucket);

        // 1. 마감된(Closed) 캔들만 캐시 또는 DB에서 조회 (캐싱 주체)
        List<OhlcCandleSnapshot> closedCandles = chartStore.get(symbolId, interval, candles, endExclusive)
                .orElseGet(() -> loadAndCache(symbolId, interval, candles, endExclusive));

        // 2. 현재 진행 중인(Live) 캔들은 항상 Redis에서 실시간으로 조회하여 병합 (캐싱 제외)
        return mergeRealTimeCandleIntoSnapshot(closedCandles, symbolId, base1mBucket, interval);
    }

    private List<OhlcCandleSnapshot> loadAndCache(Long symbolId, OhlcInterval interval, int candles,
            LocalDateTime endExclusive) {
        List<OhlcCandleSnapshot> result = loadClosedCandles(symbolId, interval, candles, endExclusive);
        chartStore.put(symbolId, interval, candles, endExclusive, result);

        return result;
    }

    private List<OhlcCandleSnapshot> loadClosedCandles(Long symbolId, OhlcInterval interval, int candles,
            LocalDateTime endExclusive) {
        LocalDateTime startInclusive = endExclusive.minus(interval.duration().multipliedBy(candles));

        return switch (interval) {
            case M1 -> toSnapshots(ohlc1mService.findCandlesInBucketRange(symbolId, startInclusive, endExclusive));
            case M5 -> toSnapshots(ohlc5mService.findCandlesInBucketRange(symbolId, startInclusive, endExclusive));
            case M30 -> toSnapshots(ohlc30mService.findCandlesInBucketRange(symbolId, startInclusive, endExclusive));
        };
    }

    private List<OhlcCandleSnapshot> toSnapshots(List<? extends AbstractOhlc> candles) {
        return candles.stream()
                .map(OhlcCandleSnapshot::from)
                .toList();
    }

    /**
     * 지정된 Snapshot 리스트에 현재 처리 중인 (Redis) 실시간 캔들을 덮어쓰기 병합한다.
     */
    private List<OhlcCandleSnapshot> mergeRealTimeCandleIntoSnapshot(
            List<OhlcCandleSnapshot> snapshots, Long symbolId,
            LocalDateTime baseBucket, OhlcInterval interval) {

        if (liveKlineRepository.isEmpty()) {
            return snapshots;
        }

        Symbol symbol = symbolService.findSymbol(symbolId);
        Optional<KlineEvent> liveKlineOpt = liveKlineRepository.get().findBySymbolAndInterval(
                symbol.getSymbol(), interval.name());

        if (liveKlineOpt.isEmpty()) {
            return snapshots;
        }

        KlineEvent liveKline = liveKlineOpt.get();
        LocalDateTime liveBucketTime = LocalDateTime.ofEpochSecond(liveKline.startTime(), 0, ZoneOffset.UTC);

        // Ensure that the live kline matches the requested timeframe
        if (!liveBucketTime.equals(baseBucket)) {
            return snapshots;
        }

        OhlcCandleSnapshot liveSnapshot = new OhlcCandleSnapshot(
                liveBucketTime,
                liveBucketTime.toEpochSecond(ZoneOffset.UTC),
                liveKline.open(),
                liveKline.high(),
                liveKline.low(),
                liveKline.close(),
                liveKline.volume());

        List<OhlcCandleSnapshot> result = new ArrayList<>(snapshots);

        // 해당 M5/M30 버킷을 찾아서 OHLCV 합산
        boolean replaced = false;
        for (int i = 0; i < result.size(); i++) {
            if (result.get(i).bucketTime().equals(baseBucket)) {
                result.set(i, liveSnapshot);
                replaced = true;
                break;
            }
        }

        if (!replaced) {
            result.add(liveSnapshot);
        }

        return result;
    }
}
