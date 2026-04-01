package com.coinflow.chart.service;

import com.coinflow.domain.ohlc.cache.OhlcChartStore;
import com.coinflow.domain.ohlc.constant.OhlcInterval;
import com.coinflow.domain.ohlc.domain.AbstractOhlc;
import com.coinflow.domain.ohlc.repository.LiveKlineRepository;
import com.coinflow.domain.ohlc.service.Ohlc1mService;
import com.coinflow.domain.ohlc.service.Ohlc30mService;
import com.coinflow.domain.ohlc.service.Ohlc5mService;
import com.coinflow.domain.ohlc.snapshot.OhlcCandleSnapshot;
import com.coinflow.domain.symbol.domain.Symbol;
import com.coinflow.domain.symbol.service.SymbolService;
import com.coinflow.event.kline.KlineEvent;
import com.coinflow.util.TimeBucket;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
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

    public List<OhlcCandleSnapshot> show(Long symbolId, OhlcInterval interval, int candles, LocalDateTime to) {
        Symbol symbol = symbolService.findSymbol(symbolId);

        // 현재 시점(Live)인지 과거 시점(Cursor-based)인지 확인하여 기준 시점(Instant) 결정
        Instant baseInstant = (to != null) ? to.toInstant(ZoneOffset.UTC) : clock.instant();
        
        LocalDateTime base1mBucket = TimeBucket.to1m(baseInstant);
        LocalDateTime endExclusive = interval.resolveBucketStart(base1mBucket);

        // 1. 마감된(Closed) 캔들: 캐시에 있으면 반환, 없으면 loader(DB 조회)를 1회만 실행
        List<OhlcCandleSnapshot> closedCandles = chartStore.getOrLoad(
                symbolId, interval, candles, endExclusive,
                () -> loadClosedCandles(symbolId, interval, candles, endExclusive)
        );

        // 2. 현재 진행 중인(Live) 캔들 병합: 'to'가 없는 현재 조회인 경우에만 수행
        if (to == null) {
            return mergeRealTimeCandleIntoSnapshot(closedCandles, symbol, base1mBucket, interval);
        }

        return closedCandles;
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
            List<OhlcCandleSnapshot> snapshots, Symbol symbol,
            LocalDateTime baseBucket, OhlcInterval interval) {

        if (liveKlineRepository.isEmpty()) {
            return snapshots;
        }

        Optional<KlineEvent> liveKlineOpt = liveKlineRepository.get().findBySymbolAndInterval(
                symbol.getSymbol(), interval.name());

        if (liveKlineOpt.isEmpty()) {
            return snapshots;
        }

        KlineEvent liveKline = liveKlineOpt.get();
        LocalDateTime liveBucketTime = LocalDateTime.ofEpochSecond(liveKline.startTime(), 0, ZoneOffset.UTC);

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

