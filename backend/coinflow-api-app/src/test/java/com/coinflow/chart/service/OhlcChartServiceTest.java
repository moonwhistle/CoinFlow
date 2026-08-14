package com.coinflow.chart.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.coinflow.chart.cache.hot.OhlcHotWindow;
import com.coinflow.chart.cache.hot.OhlcHotWindowStore;
import com.coinflow.domain.ohlc.cache.OhlcChartStore;
import com.coinflow.domain.ohlc.constant.OhlcInterval;
import com.coinflow.domain.ohlc.repository.LiveKlineRepository;
import com.coinflow.domain.ohlc.repository.OhlcWindowRepository;
import com.coinflow.domain.ohlc.service.Ohlc1mService;
import com.coinflow.domain.ohlc.service.Ohlc30mService;
import com.coinflow.domain.ohlc.service.Ohlc5mService;
import com.coinflow.domain.ohlc.snapshot.OhlcCandleSnapshot;
import com.coinflow.domain.symbol.domain.Symbol;
import com.coinflow.domain.symbol.service.SymbolService;
import com.coinflow.event.kline.KlineEvent;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class OhlcChartServiceTest {

    @Mock
    private OhlcChartStore chartStore;

    @Mock
    private OhlcWindowRepository ohlcWindowRepository;

    @Mock
    private OhlcHotWindowStore hotWindowStore;

    @Mock
    private Ohlc1mService ohlc1mService;

    @Mock
    private Ohlc5mService ohlc5mService;

    @Mock
    private Ohlc30mService ohlc30mService;

    @Mock
    private LiveKlineRepository liveKlineRepository;

    @Mock
    private SymbolService symbolService;

    private Clock clock;
    private OhlcChartService service;
    private Symbol symbol;

    @BeforeEach
    void setUp() {
        clock = Clock.fixed(Instant.parse("2026-08-14T12:03:30Z"), ZoneOffset.UTC);
        service = new OhlcChartService(
                chartStore,
                ohlcWindowRepository,
                hotWindowStore,
                clock,
                ohlc1mService,
                ohlc5mService,
                ohlc30mService,
                Optional.of(liveKlineRepository),
                symbolService
        );
        symbol = Symbol.builder().id(1L).symbol("btcusdt").build();
    }

    @Test
    void freshHotWindowServesFinalizedAndLiveCandlesWithoutRedis() {
        long first = Instant.parse("2026-08-14T12:01:00Z").getEpochSecond();
        long second = Instant.parse("2026-08-14T12:02:00Z").getEpochSecond();
        long liveStart = Instant.parse("2026-08-14T12:03:00Z").getEpochSecond();
        KlineEvent live = event(liveStart, "103");
        OhlcHotWindow window = new OhlcHotWindow(
                List.of(snapshot(first, "101"), snapshot(second, "102")),
                live,
                clock.instant(),
                0
        );

        when(symbolService.findSymbol(1L)).thenReturn(symbol);
        when(hotWindowStore.get("btcusdt", "M1")).thenReturn(Optional.of(window));

        List<OhlcCandleSnapshot> result = service.show(1L, OhlcInterval.M1, 2, null);

        assertEquals(3, result.size());
        assertEquals(liveStart, result.get(2).epochSeconds());
        assertEquals("103", result.get(2).closePrice().toPlainString());
        verify(ohlcWindowRepository, never()).findRange("btcusdt", "M1", second + 60, 1000);
        verify(liveKlineRepository, never()).findBySymbolAndInterval("btcusdt", "M1");
    }

    private OhlcCandleSnapshot snapshot(long epochSeconds, String close) {
        BigDecimal value = new BigDecimal(close);
        return new OhlcCandleSnapshot(
                LocalDateTime.ofEpochSecond(epochSeconds, 0, ZoneOffset.UTC),
                epochSeconds,
                value,
                value,
                value,
                value,
                BigDecimal.ONE
        );
    }

    private KlineEvent event(long startTime, String close) {
        BigDecimal value = new BigDecimal(close);
        return KlineEvent.builder()
                .symbol("btcusdt")
                .interval("M1")
                .startTime(startTime)
                .closeTime(startTime + 59)
                .open(value)
                .high(value)
                .low(value)
                .close(value)
                .volume(BigDecimal.ONE)
                .trades(1)
                .closed(false)
                .build();
    }
}
