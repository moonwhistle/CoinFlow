package com.coinflow.market.service;

import com.coinflow.domain.ohlc.policy.VolumeScaler;
import com.coinflow.domain.ohlc.repository.LiveKlineRepository;
import com.coinflow.domain.ohlc.service.Ohlc1mService;
import com.coinflow.domain.ohlc.snapshot.OhlcRangeStatistics;
import com.coinflow.domain.symbol.domain.Symbol;
import com.coinflow.domain.symbol.service.SymbolService;
import com.coinflow.event.kline.KlineEvent;
import com.coinflow.market.controller.response.MarketStats24hResponse;
import com.coinflow.util.TimeBucket;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class MarketStatsService {

    private static final Duration STATS_WINDOW = Duration.ofHours(24);
    private static final Duration CACHE_TTL = Duration.ofSeconds(5);
    private static final int CHANGE_PERCENT_SCALE = 4;
    private static final int CACHE_MAX_SIZE = 1_000;

    private final Clock clock;
    private final SymbolService symbolService;
    private final Ohlc1mService ohlc1mService;
    private final Optional<LiveKlineRepository> liveKlineRepository;
    private final Cache<Long, Optional<MarketStats24hResponse>> statsCache = Caffeine.newBuilder()
            .expireAfterWrite(CACHE_TTL)
            .maximumSize(CACHE_MAX_SIZE)
            .build();

    public Optional<MarketStats24hResponse> get24hStats(Long symbolId) {
        return statsCache.get(symbolId, this::load24hStats);
    }

    private Optional<MarketStats24hResponse> load24hStats(Long symbolId) {
        Symbol symbol = symbolService.findSymbol(symbolId);
        Instant now = clock.instant();
        Instant windowStart = now.minus(STATS_WINDOW);
        LocalDateTime currentBucket = TimeBucket.to1m(now);
        LocalDateTime startInclusive = LocalDateTime.ofInstant(windowStart, ZoneOffset.UTC);

        Optional<OhlcRangeStatistics> finalized = ohlc1mService.summarizeRange(
                symbolId, startInclusive, currentBucket);
        Optional<KlineEvent> live = findCurrentLiveCandle(symbol.getSymbol(), currentBucket);

        if (finalized.isEmpty() && live.isEmpty()) {
            return Optional.empty();
        }

        BigDecimal openPrice = finalized.map(OhlcRangeStatistics::openPrice)
                .orElseGet(() -> live.orElseThrow().open());
        BigDecimal currentPrice = live.map(KlineEvent::close)
                .orElseGet(() -> finalized.orElseThrow().closePrice());
        BigDecimal highPrice = max(
                finalized.map(OhlcRangeStatistics::highPrice).orElse(null),
                live.map(KlineEvent::high).orElse(null));
        BigDecimal lowPrice = min(
                finalized.map(OhlcRangeStatistics::lowPrice).orElse(null),
                live.map(KlineEvent::low).orElse(null));

        long scaledFinalizedVolume = finalized.map(OhlcRangeStatistics::volume).orElse(0L);
        BigDecimal volume = VolumeScaler.toBigDecimal(scaledFinalizedVolume)
                .add(live.map(KlineEvent::volume).orElse(BigDecimal.ZERO));

        return Optional.of(new MarketStats24hResponse(
                symbolId,
                symbol.getSymbol(),
                windowStart.toEpochMilli(),
                now.toEpochMilli(),
                live.map(KlineEvent::startTime).orElse(null),
                live.map(KlineEvent::volume).orElse(BigDecimal.ZERO),
                openPrice,
                currentPrice,
                highPrice,
                lowPrice,
                volume,
                calculateChangePercent(openPrice, currentPrice)));
    }

    private Optional<KlineEvent> findCurrentLiveCandle(String symbol, LocalDateTime currentBucket) {
        if (liveKlineRepository.isEmpty()) {
            return Optional.empty();
        }

        return liveKlineRepository.get().findBySymbolAndInterval(symbol, "M1")
                .filter(event -> event.startTime() == currentBucket.toEpochSecond(ZoneOffset.UTC));
    }

    private BigDecimal calculateChangePercent(BigDecimal openPrice, BigDecimal currentPrice) {
        if (openPrice.signum() == 0) {
            return BigDecimal.ZERO.setScale(CHANGE_PERCENT_SCALE);
        }

        return currentPrice.subtract(openPrice)
                .multiply(BigDecimal.valueOf(100))
                .divide(openPrice, CHANGE_PERCENT_SCALE, RoundingMode.HALF_UP);
    }

    private BigDecimal max(BigDecimal first, BigDecimal second) {
        if (first == null) {
            return second;
        }
        if (second == null) {
            return first;
        }
        return first.max(second);
    }

    private BigDecimal min(BigDecimal first, BigDecimal second) {
        if (first == null) {
            return second;
        }
        if (second == null) {
            return first;
        }
        return first.min(second);
    }
}
