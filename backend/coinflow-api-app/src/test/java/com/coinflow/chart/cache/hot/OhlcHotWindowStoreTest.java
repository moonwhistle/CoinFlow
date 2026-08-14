package com.coinflow.chart.cache.hot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.coinflow.domain.ohlc.snapshot.OhlcCandleSnapshot;
import com.coinflow.event.kline.KlineEvent;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class OhlcHotWindowStoreTest {

    private OhlcHotWindowStore store;

    @BeforeEach
    void setUp() {
        store = new OhlcHotWindowStore(new SimpleMeterRegistry());
    }

    @Test
    void replacesTheWholeWindowAndReturnsTheRequestedTail() {
        KlineEvent live = event(180, "103", false);
        store.replace(
                "btcusdt",
                "M1",
                List.of(snapshot(60, "101"), snapshot(120, "102")),
                Optional.of(live),
                Instant.parse("2026-08-14T00:03:00Z")
        );

        OhlcHotWindow window = store.get("BTCUSDT", "M1").orElseThrow();

        assertEquals(List.of(snapshot(120, "102")), window.findFinalizedRange(180, 1));
        assertEquals(live, window.liveCandle());
    }

    @Test
    void doesNotOverwriteAnEventThatArrivedDuringPolling() {
        store.replace(
                "btcusdt", "M1", List.of(snapshot(60, "101")), Optional.empty(), Instant.now());
        long versionBeforePoll = store.eventVersion("btcusdt", "M1");

        KlineEvent newerLive = event(120, "105", false);
        store.applyEvent(newerLive);

        boolean replaced = store.replaceIfVersion(
                "btcusdt",
                "M1",
                List.of(snapshot(60, "101")),
                Optional.of(event(120, "102", false)),
                Instant.now(),
                versionBeforePoll
        );

        assertFalse(replaced);
        assertEquals(newerLive, store.get("btcusdt", "M1").orElseThrow().liveCandle());
    }

    @Test
    void closedEventMovesTheLiveCandleIntoTheFinalizedWindow() {
        store.applyEvent(event(120, "102", false));
        store.applyEvent(event(120, "104", true));

        OhlcHotWindow window = store.get("btcusdt", "M1").orElseThrow();

        assertTrue(window.liveCandleOptional().isEmpty());
        assertEquals("104", window.finalizedCandles().get(0).closePrice().toPlainString());
    }

    private OhlcCandleSnapshot snapshot(long epochSeconds, String close) {
        LocalDateTime bucket = LocalDateTime.ofEpochSecond(epochSeconds, 0, ZoneOffset.UTC);
        BigDecimal closePrice = new BigDecimal(close);
        return new OhlcCandleSnapshot(
                bucket,
                epochSeconds,
                closePrice,
                closePrice,
                closePrice,
                closePrice,
                BigDecimal.ONE
        );
    }

    private KlineEvent event(long startTime, String close, boolean closed) {
        BigDecimal closePrice = new BigDecimal(close);
        return KlineEvent.builder()
                .symbol("btcusdt")
                .interval("M1")
                .startTime(startTime)
                .closeTime(startTime + 59)
                .open(closePrice)
                .high(closePrice)
                .low(closePrice)
                .close(closePrice)
                .volume(BigDecimal.ONE)
                .trades(1)
                .closed(closed)
                .build();
    }
}
