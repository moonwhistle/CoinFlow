package com.coinflow.aggregation.service.rollup.upserter;

import com.coinflow.aggregation.process.rollup.OhlcRollup;
import com.coinflow.domain.ohlc.constant.OhlcInterval;
import com.coinflow.domain.symbol.domain.Symbol;
import java.time.LocalDateTime;

public interface OhlcRollupUpserter {

    OhlcInterval supports();

    void upsert(Long symbolId, Symbol symbol, LocalDateTime bucketTime, OhlcRollup rollup);
}
