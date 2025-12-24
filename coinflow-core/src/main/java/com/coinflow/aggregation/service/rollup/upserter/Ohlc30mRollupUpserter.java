package com.coinflow.aggregation.service.rollup.upserter;

import com.coinflow.aggregation.process.rollup.OhlcRollup;
import com.coinflow.domain.ohlc.constant.OhlcInterval;
import com.coinflow.domain.ohlc.service.Ohlc30mService;
import com.coinflow.domain.symbol.domain.Symbol;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class Ohlc30mRollupUpserter implements OhlcRollupUpserter{

    private final Ohlc30mService ohlc30mService;

    @Override
    public OhlcInterval supports() {
        return OhlcInterval.M30;
    }

    @Override
    public void upsert(Long symbolId, Symbol symbol, LocalDateTime bucketTime, OhlcRollup rollup) {
        ohlc30mService.upsert(symbolId, symbol, bucketTime, rollup);
    }
}
