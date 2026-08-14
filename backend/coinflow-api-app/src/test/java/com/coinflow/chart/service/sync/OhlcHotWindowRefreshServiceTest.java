package com.coinflow.chart.service.sync;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.coinflow.chart.cache.hot.OhlcHotWindowStore;
import com.coinflow.domain.ohlc.constant.OhlcInterval;
import com.coinflow.domain.ohlc.repository.LiveKlineRepository;
import com.coinflow.domain.ohlc.repository.OhlcWindowRepository;
import com.coinflow.domain.ohlc.snapshot.OhlcCandleSnapshot;
import com.coinflow.domain.symbol.domain.Symbol;
import com.coinflow.domain.symbol.service.SymbolService;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class OhlcHotWindowRefreshServiceTest {

    @Mock
    private OhlcWindowRepository ohlcWindowRepository;

    @Mock
    private LiveKlineRepository liveKlineRepository;

    @Mock
    private OhlcHotWindowStore hotWindowStore;

    @Mock
    private SymbolService symbolService;

    @Test
    void refreshReadsTheEntireRedisWindowAndAtomicallyReplacesCaffeine() {
        Clock clock = Clock.fixed(Instant.parse("2026-08-14T12:03:30Z"), ZoneOffset.UTC);
        OhlcHotWindowRefreshService service = new OhlcHotWindowRefreshService(
                ohlcWindowRepository,
                liveKlineRepository,
                hotWindowStore,
                symbolService,
                clock
        );
        Symbol symbol = Symbol.builder().id(1L).symbol("btcusdt").build();
        long endExclusive = Instant.parse("2026-08-14T12:03:00Z").getEpochSecond();
        List<OhlcCandleSnapshot> finalized = List.of(snapshot(endExclusive - 60));

        when(hotWindowStore.eventVersion("btcusdt", "M1")).thenReturn(7L);
        when(ohlcWindowRepository.findRange("btcusdt", "M1", endExclusive, 1000))
                .thenReturn(finalized);
        when(liveKlineRepository.findBySymbolAndInterval("btcusdt", "M1"))
                .thenReturn(Optional.empty());

        service.refresh(symbol, OhlcInterval.M1);

        verify(hotWindowStore).replaceIfVersion(
                eq("btcusdt"),
                eq("M1"),
                eq(finalized),
                eq(Optional.empty()),
                any(Instant.class),
                eq(7L)
        );
    }

    private OhlcCandleSnapshot snapshot(long epochSeconds) {
        LocalDateTime bucket = LocalDateTime.ofEpochSecond(epochSeconds, 0, ZoneOffset.UTC);
        return new OhlcCandleSnapshot(
                bucket,
                epochSeconds,
                BigDecimal.ONE,
                BigDecimal.ONE,
                BigDecimal.ONE,
                BigDecimal.ONE,
                BigDecimal.ONE
        );
    }
}
