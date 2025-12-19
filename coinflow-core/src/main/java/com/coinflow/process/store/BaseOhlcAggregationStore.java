package com.coinflow.process.store;

import com.coinflow.domain.symbol.domain.Symbol;
import com.coinflow.domain.tick.event.TickRawEvent;
import com.coinflow.process.aggregate.AggregateKey;
import com.coinflow.process.aggregate.OhlcAccumulator;
import com.coinflow.process.policy.VolumeScaler;
import java.time.LocalDateTime;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public abstract class BaseOhlcAggregationStore implements OhlcAggregationStore {

    private final ConcurrentHashMap<AggregateKey, OhlcAccumulator> store = new ConcurrentHashMap<>();

    protected abstract LocalDateTime resolveBucket(TickRawEvent event);

    @Override
    public final void accumulate(Symbol symbol, TickRawEvent event) {
        LocalDateTime bucket = resolveBucket(event);
        long volume = VolumeScaler.toLong(event.quantity());
        AggregateKey key = new AggregateKey(symbol.getId(), bucket);

        store.compute(key, (k, acc) -> {
            if (acc == null) {
                return OhlcAccumulator.first(event.price(), volume, event.eventTime());
            }
            acc.apply(event.price(), volume, event.eventTime());
            return acc;
        });
    }

    @Override
    public final Set<AggregateKey> keysSnapshot() {
        return Set.copyOf(store.keySet());
    }

    @Override
    public final void remove(AggregateKey key) {
        store.remove(key);
    }

    @Override
    public final int size() {
        return store.size();
    }

    @Override
    public OhlcAccumulator peek(AggregateKey key) {
        return store.get(key);
    }
}
