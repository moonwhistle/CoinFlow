package com.coinflow.market.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

import com.coinflow.domain.ohlc.repository.LiveKlineRepository;
import com.coinflow.domain.ohlc.service.Ohlc1mService;
import com.coinflow.domain.ohlc.snapshot.OhlcRangeStatistics;
import com.coinflow.domain.symbol.domain.Symbol;
import com.coinflow.domain.symbol.domain.vo.MarketType;
import com.coinflow.domain.symbol.service.SymbolService;
import com.coinflow.event.kline.KlineEvent;
import com.coinflow.market.controller.response.MarketStats24hResponse;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class MarketStatsServiceTest {

    private static final Instant NOW = Instant.parse("2026-08-14T12:34:56Z");
    private static final LocalDateTime CURRENT_BUCKET = LocalDateTime.of(2026, 8, 14, 12, 34);
    private static final LocalDateTime WINDOW_START = LocalDateTime.of(2026, 8, 13, 12, 34, 56);

    @Mock
    private SymbolService symbolService;

    @Mock
    private Ohlc1mService ohlc1mService;

    @Mock
    private LiveKlineRepository liveKlineRepository;

    private MarketStatsService marketStatsService;

    @BeforeEach
    void setUp() {
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        marketStatsService = new MarketStatsService(
                clock,
                symbolService,
                ohlc1mService,
                Optional.of(liveKlineRepository));

        given(symbolService.findSymbol(1L)).willReturn(symbol());
    }

    @Test
    void combinesFinalizedAndLiveCandlesInto24hStats() {
        OhlcRangeStatistics finalized = new OhlcRangeStatistics(
                LocalDateTime.of(2026, 8, 13, 12, 35),
                LocalDateTime.of(2026, 8, 14, 12, 33),
                new BigDecimal("100.00"),
                new BigDecimal("108.00"),
                new BigDecimal("95.00"),
                new BigDecimal("107.00"),
                1_000_000_000L);
        KlineEvent live = liveCandle(
                CURRENT_BUCKET,
                "107.00",
                "112.00",
                "101.00",
                "110.00",
                "1.25000000");

        given(ohlc1mService.summarizeRange(1L, WINDOW_START, CURRENT_BUCKET))
                .willReturn(Optional.of(finalized));
        given(liveKlineRepository.findBySymbolAndInterval("btcusdt", "M1"))
                .willReturn(Optional.of(live));

        MarketStats24hResponse response = marketStatsService.get24hStats(1L).orElseThrow();

        assertThat(response.openPrice()).isEqualByComparingTo("100.00");
        assertThat(response.currentPrice()).isEqualByComparingTo("110.00");
        assertThat(response.highPrice()).isEqualByComparingTo("112.00");
        assertThat(response.lowPrice()).isEqualByComparingTo("95.00");
        assertThat(response.volume()).isEqualByComparingTo("11.25000000");
        assertThat(response.changePercent()).isEqualByComparingTo("10.0000");
        assertThat(response.windowStartEpochMillis()).isEqualTo(NOW.minusSeconds(86_400).toEpochMilli());
        assertThat(response.asOfEpochMillis()).isEqualTo(NOW.toEpochMilli());
        assertThat(response.currentCandleStartEpochSeconds())
                .isEqualTo(CURRENT_BUCKET.toEpochSecond(ZoneOffset.UTC));
        assertThat(response.currentCandleVolume()).isEqualByComparingTo("1.25000000");
        verify(ohlc1mService).summarizeRange(1L, WINDOW_START, CURRENT_BUCKET);
    }

    @Test
    void returnsStatsFromLiveCandleWhenThereAreNoFinalizedCandles() {
        KlineEvent live = liveCandle(
                CURRENT_BUCKET,
                "100.00",
                "105.00",
                "99.00",
                "102.00",
                "2.50000000");

        given(ohlc1mService.summarizeRange(1L, WINDOW_START, CURRENT_BUCKET))
                .willReturn(Optional.empty());
        given(liveKlineRepository.findBySymbolAndInterval("btcusdt", "M1"))
                .willReturn(Optional.of(live));

        MarketStats24hResponse response = marketStatsService.get24hStats(1L).orElseThrow();

        assertThat(response.openPrice()).isEqualByComparingTo("100.00");
        assertThat(response.currentPrice()).isEqualByComparingTo("102.00");
        assertThat(response.highPrice()).isEqualByComparingTo("105.00");
        assertThat(response.lowPrice()).isEqualByComparingTo("99.00");
        assertThat(response.volume()).isEqualByComparingTo("2.50000000");
        assertThat(response.changePercent()).isEqualByComparingTo("2.0000");
    }

    @Test
    void ignoresStaleLiveCandleAndUsesFinalizedStats() {
        OhlcRangeStatistics finalized = new OhlcRangeStatistics(
                LocalDateTime.of(2026, 8, 13, 12, 35),
                LocalDateTime.of(2026, 8, 14, 12, 33),
                new BigDecimal("100.00"),
                new BigDecimal("110.00"),
                new BigDecimal("90.00"),
                new BigDecimal("105.00"),
                500_000_000L);
        KlineEvent staleLive = liveCandle(
                CURRENT_BUCKET.minusMinutes(1),
                "105.00",
                "200.00",
                "1.00",
                "150.00",
                "10.00000000");

        given(ohlc1mService.summarizeRange(1L, WINDOW_START, CURRENT_BUCKET))
                .willReturn(Optional.of(finalized));
        given(liveKlineRepository.findBySymbolAndInterval("btcusdt", "M1"))
                .willReturn(Optional.of(staleLive));

        MarketStats24hResponse response = marketStatsService.get24hStats(1L).orElseThrow();

        assertThat(response.currentPrice()).isEqualByComparingTo("105.00");
        assertThat(response.highPrice()).isEqualByComparingTo("110.00");
        assertThat(response.lowPrice()).isEqualByComparingTo("90.00");
        assertThat(response.volume()).isEqualByComparingTo("5.00000000");
        assertThat(response.changePercent()).isEqualByComparingTo("5.0000");
        assertThat(response.currentCandleStartEpochSeconds()).isNull();
        assertThat(response.currentCandleVolume()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    void returnsEmptyWhenNoMarketDataExists() {
        given(ohlc1mService.summarizeRange(1L, WINDOW_START, CURRENT_BUCKET))
                .willReturn(Optional.empty());
        given(liveKlineRepository.findBySymbolAndInterval("btcusdt", "M1"))
                .willReturn(Optional.empty());

        assertThat(marketStatsService.get24hStats(1L)).isEmpty();
    }

    private Symbol symbol() {
        return Symbol.builder()
                .id(1L)
                .symbol("btcusdt")
                .exchange("BINANCE")
                .name("Bitcoin / USDT")
                .active(true)
                .marketType(MarketType.SPOT)
                .providerSymbol("btcusdt")
                .build();
    }

    private KlineEvent liveCandle(
            LocalDateTime bucket,
            String open,
            String high,
            String low,
            String close,
            String volume) {
        long startTime = bucket.toEpochSecond(ZoneOffset.UTC);
        return KlineEvent.builder()
                .symbol("btcusdt")
                .interval("M1")
                .startTime(startTime)
                .closeTime(startTime + 59)
                .open(new BigDecimal(open))
                .high(new BigDecimal(high))
                .low(new BigDecimal(low))
                .close(new BigDecimal(close))
                .volume(new BigDecimal(volume))
                .trades(1)
                .closed(false)
                .build();
    }
}
