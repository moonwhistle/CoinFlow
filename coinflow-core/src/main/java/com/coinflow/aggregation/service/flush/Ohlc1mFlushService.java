package com.coinflow.aggregation.service.flush;

import com.coinflow.domain.ohlc.service.Ohlc1mService;
import com.coinflow.domain.symbol.domain.Symbol;
import com.coinflow.domain.symbol.service.SymbolService;
import com.coinflow.aggregation.process.aggregate.AggregateKey;
import com.coinflow.aggregation.process.aggregate.OhlcAccumulator;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class Ohlc1mFlushService {

    private final Ohlc1mService ohlc1mService;
    private final SymbolService symbolService;
    private final Ohlc1mFlushedEventPublisher eventPublisher;

    @Transactional
    public void flush(AggregateKey key, OhlcAccumulator acc) {
        Symbol symbol = symbolService.findSymbol(key.symbolId());
        ohlc1mService.applyAndSave(symbol, key.bucket(), acc);
        eventPublisher.publish(symbol.getId(), key.bucket());
    }
}
