package com.coinflow.process.service;

import com.coinflow.domain.ohlc.constant.OhlcInterval;
import com.coinflow.domain.ohlc.domain.Ohlc1m;
import com.coinflow.domain.ohlc.service.Ohlc1mService;
import com.coinflow.domain.ohlc.service.Ohlc5mService;
import com.coinflow.domain.symbol.domain.Symbol;
import com.coinflow.domain.symbol.service.SymbolService;
import com.coinflow.process.rollup.OhlcRollupCalculator;
import com.coinflow.process.time.BucketCloseChecker;
import java.time.LocalDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@Slf4j
public class Ohlc5mRollupService {

    private static final OhlcInterval INTERVAL = OhlcInterval.M5;

    private final Ohlc1mService ohlc1mService;
    private final Ohlc5mService ohlc5mService;
    private final SymbolService symbolService;
    private final BucketCloseChecker bucketCloseChecker;

    public void rollupIfClosed(Long symbolId, LocalDateTime bucketStart) {
        if (!bucketCloseChecker.isClosed(INTERVAL, bucketStart)) {
            return;
        }

        LocalDateTime endExclusive = bucketStart.plus(INTERVAL.duration());
        List<Ohlc1m> candles = ohlc1mService.findCandlesInBucketRange(symbolId, bucketStart, endExclusive);

        if (candles.isEmpty()) {
            return;
        }

        Symbol symbol = symbolService.findSymbol(symbolId);
        OhlcRollupCalculator.rollup(candles)
                .ifPresent(rollup -> {
                    ohlc5mService.upsert(symbol, bucketStart, rollup);
                    log.info(
                            "5m rollup upsert. symbol={}, bucketStart={}, count={}",
                            symbolId,
                            bucketStart,
                            candles.size()
                    );
                });
    }
}
