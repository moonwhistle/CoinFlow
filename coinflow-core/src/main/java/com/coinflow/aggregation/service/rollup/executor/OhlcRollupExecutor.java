package com.coinflow.aggregation.service.rollup.executor;

import com.coinflow.aggregation.process.rollup.OhlcRollupCalculator;
import com.coinflow.aggregation.process.time.BucketCloseChecker;
import com.coinflow.aggregation.service.rollup.constant.RollupLogMessages;
import com.coinflow.aggregation.service.rollup.upserter.OhlcRollupUpserter;
import com.coinflow.aggregation.service.rollup.upserter.OhlcRollupUpserterRegistry;
import com.coinflow.domain.ohlc.constant.OhlcInterval;
import com.coinflow.domain.ohlc.domain.Ohlc1m;
import com.coinflow.domain.ohlc.service.Ohlc1mService;
import com.coinflow.domain.symbol.domain.Symbol;
import com.coinflow.domain.symbol.service.SymbolService;
import java.time.LocalDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class OhlcRollupExecutor {

    private final Ohlc1mService ohlc1mService;
    private final SymbolService symbolService;
    private final BucketCloseChecker bucketCloseChecker;
    private final OhlcRollupUpserterRegistry upserterRegistry;

    public void rollupFrom1mIfClosed(
            Long symbolId,
            OhlcInterval interval,
            LocalDateTime bucketStart
    ) {
        if (bucketCloseChecker.isOpen(interval, bucketStart)) {
            return;
        }

        LocalDateTime endExclusive = bucketStart.plus(interval.duration());
        List<Ohlc1m> candles = ohlc1mService.findCandlesInBucketRange(symbolId, bucketStart, endExclusive);

        if (candles.isEmpty()) {
            return;
        }

        Symbol symbol = symbolService.findSymbol(symbolId);
        OhlcRollupCalculator.rollup(candles)
                .ifPresent(rollup -> {
                    OhlcRollupUpserter upserter = upserterRegistry.get(interval);
                    upserter.upsert(symbolId, symbol, bucketStart, rollup);

                    log.info(
                            RollupLogMessages.ROLLUP_UPSERT,
                            interval,
                            symbolId,
                            bucketStart,
                            candles.size()
                    );
                });
    }
}
