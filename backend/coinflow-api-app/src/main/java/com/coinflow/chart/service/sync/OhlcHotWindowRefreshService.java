package com.coinflow.chart.service.sync;

import com.coinflow.chart.cache.hot.OhlcHotWindowStore;
import com.coinflow.domain.ohlc.constant.OhlcInterval;
import com.coinflow.domain.ohlc.constant.OhlcWindowPolicy;
import com.coinflow.domain.ohlc.repository.LiveKlineRepository;
import com.coinflow.domain.ohlc.repository.OhlcWindowRepository;
import com.coinflow.domain.ohlc.snapshot.OhlcCandleSnapshot;
import com.coinflow.domain.symbol.domain.Symbol;
import com.coinflow.domain.symbol.service.SymbolService;
import com.coinflow.event.kline.KlineEvent;
import com.coinflow.util.TimeBucket;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(
        name = "coinflow.chart.hot-window.enabled",
        havingValue = "true",
        matchIfMissing = true
)
public class OhlcHotWindowRefreshService {

    private final OhlcWindowRepository ohlcWindowRepository;
    private final LiveKlineRepository liveKlineRepository;
    private final OhlcHotWindowStore hotWindowStore;
    private final SymbolService symbolService;
    private final Clock clock;

    private volatile List<Symbol> symbols = List.of();

    @EventListener(ApplicationReadyEvent.class)
    public void loadSymbols() {
        refreshSymbols();
    }

    @Scheduled(
            fixedDelayString = "${coinflow.chart.hot-window.symbol-refresh-interval-ms:60000}",
            initialDelayString = "${coinflow.chart.hot-window.symbol-refresh-interval-ms:60000}"
    )
    public void refreshSymbols() {
        try {
            symbols = List.copyOf(symbolService.findAll());
        } catch (Exception e) {
            log.error("[HOT-WINDOW] Failed to refresh symbols; keeping the previous symbol list", e);
        }
    }

    @Scheduled(
            fixedDelayString = "${coinflow.chart.hot-window.refresh-interval-ms:1000}",
            initialDelayString = "${coinflow.chart.hot-window.initial-delay-ms:1000}"
    )
    public void refreshAll() {
        for (Symbol symbol : symbols) {
            for (OhlcInterval interval : OhlcInterval.values()) {
                refresh(symbol, interval);
            }
        }
    }

    public void refresh(Symbol symbol, OhlcInterval interval) {
        try {
            long expectedVersion = hotWindowStore.eventVersion(
                    symbol.getSymbol(), interval.name());
            LocalDateTime currentBucket = TimeBucket.to1m(clock.instant());
            long endExclusive = interval.resolveBucketStart(currentBucket)
                    .toEpochSecond(ZoneOffset.UTC);
            List<OhlcCandleSnapshot> finalizedCandles = ohlcWindowRepository.findRange(
                    symbol.getSymbol(),
                    interval.name(),
                    endExclusive,
                    OhlcWindowPolicy.MAX_SIZE
            );
            Optional<KlineEvent> liveCandle = liveKlineRepository.findBySymbolAndInterval(
                    symbol.getSymbol(), interval.name());

            boolean replaced = hotWindowStore.replaceIfVersion(
                    symbol.getSymbol(),
                    interval.name(),
                    finalizedCandles,
                    liveCandle,
                    Instant.now(clock),
                    expectedVersion
            );
            if (!replaced) {
                log.trace("[HOT-WINDOW] Skipped stale poll result for {} {}",
                        symbol.getSymbol(), interval);
            }
        } catch (Exception e) {
            log.error("[HOT-WINDOW] Refresh failed for {} {}; keeping the previous snapshot",
                    symbol.getSymbol(), interval, e);
        }
    }
}
