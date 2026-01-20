package com.coinflow.aggregation.service.rollup.upserter;

import com.coinflow.aggregation.process.rollup.OhlcRollup;
import com.coinflow.domain.ohlc.constant.OhlcInterval;
import com.coinflow.domain.ohlc.service.Ohlc5mService;
import com.coinflow.domain.symbol.domain.Symbol;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class Ohlc5mRollupUpserter implements OhlcRollupUpserter {

    private final Ohlc5mService ohlc5mService;

    @Override
    public OhlcInterval supports() {
        return OhlcInterval.M5;
    }

    @Override
    public void upsert(Long symbolId, Symbol symbol, LocalDateTime bucketTime, OhlcRollup rollup) {
        ohlc5mService.upsert(
                symbolId,
                symbol,
                bucketTime,
                rollup.open(),
                rollup.high(),
                rollup.low(),
                rollup.close(),
                rollup.volume()
        );
    }
}
