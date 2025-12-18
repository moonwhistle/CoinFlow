package com.coinflow.process.service;

import com.coinflow.domain.ohlc.domain.Ohlc1m;
import com.coinflow.domain.ohlc.service.Ohlc1mService;
import com.coinflow.domain.ohlc.service.Ohlc5mService;
import com.coinflow.domain.rollup.domain.vo.OhlcInterval;
import com.coinflow.domain.symbol.domain.Symbol;
import com.coinflow.process.rollup.OhlcRollupCalculator;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class Ohlc5mRollupService {

    private static final OhlcInterval SOURCE_INTERVAL = OhlcInterval.M1;
    private static final OhlcInterval TARGET_INTERVAL = OhlcInterval.M5;

    private final Ohlc1mService ohlc1mService;
    private final Ohlc5mService ohlc5mService;

    @Transactional
    public void rollupAndUpsert(LocalDateTime bucketTime) {
        LocalDateTime endExclusive = bucketTime.plus(TARGET_INTERVAL.duration());
        List<Ohlc1m> candles = ohlc1mService.findCandlesInBucketRange(bucketTime, endExclusive);

        if (candles.isEmpty()) {
            return;
        }

        Map<Long, List<Ohlc1m>> bySymbolId = candles.stream()
                .collect(Collectors.groupingBy(c -> c.getSymbol().getId()));

        for (List<Ohlc1m> symbolCandles : bySymbolId.values()) {
            Symbol symbol = symbolCandles.get(0).getSymbol();

            OhlcRollupCalculator
                    .tryRollup(symbolCandles, SOURCE_INTERVAL, TARGET_INTERVAL, bucketTime)
                    .ifPresent(rollup -> ohlc5mService.upsert(symbol, bucketTime, rollup));
        }
    }
}


