package com.coinflow.chart.cache.hot;

import com.coinflow.domain.ohlc.snapshot.OhlcCandleSnapshot;
import com.coinflow.event.kline.KlineEvent;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public record OhlcHotWindow(
        List<OhlcCandleSnapshot> finalizedCandles,
        KlineEvent liveCandle,
        Instant synchronizedAt,
        long eventVersion
) {

    public OhlcHotWindow {
        finalizedCandles = List.copyOf(finalizedCandles);
    }

    public Optional<KlineEvent> liveCandleOptional() {
        return Optional.ofNullable(liveCandle);
    }

    public List<OhlcCandleSnapshot> findFinalizedRange(long toExclusive, int limit) {
        List<OhlcCandleSnapshot> matches = finalizedCandles.stream()
                .filter(candle -> candle.epochSeconds() < toExclusive)
                .toList();
        int fromIndex = Math.max(0, matches.size() - limit);
        return new ArrayList<>(matches.subList(fromIndex, matches.size()));
    }
}
