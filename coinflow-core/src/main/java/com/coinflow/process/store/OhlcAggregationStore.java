package com.coinflow.process.store;

import com.coinflow.domain.symbol.domain.Symbol;
import com.coinflow.domain.tick.event.TickRawEvent;
import com.coinflow.process.aggregate.AggregateKey;
import com.coinflow.process.aggregate.OhlcAccumulator;
import java.util.Set;

/**
 * Tick -> OHLC  in-memory store (1m/5m/30m/1d 확장 고려)
 */
public interface OhlcAggregationStore {

    void accumulate(Symbol symbol, TickRawEvent event);

    Set<AggregateKey> keysSnapshot();

    void remove(AggregateKey key);

    OhlcAccumulator peek(AggregateKey key);

    int size();
}
