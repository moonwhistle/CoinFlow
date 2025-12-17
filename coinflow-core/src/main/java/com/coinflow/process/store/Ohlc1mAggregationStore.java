package com.coinflow.process.store;

import com.coinflow.domain.symbol.domain.Symbol;
import com.coinflow.domain.tick.event.TickRawEvent;
import com.coinflow.process.aggregate.AggregateKey;
import com.coinflow.process.aggregate.Ohlc1mAccumulator;
import com.coinflow.process.policy.VolumeScaler;
import com.coinflow.process.util.TimeBucket;
import java.time.LocalDateTime;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

@Component
public class Ohlc1mAggregationStore {

    private final ConcurrentHashMap<AggregateKey, Ohlc1mAccumulator> store = new ConcurrentHashMap<>();

    public void accumulate(Symbol symbol, TickRawEvent event) {
        LocalDateTime bucket = TimeBucket.to1m(event.eventTime());
        long volume = VolumeScaler.toLong(event.quantity());
        AggregateKey key = new AggregateKey(symbol.getId(), bucket);

        store.compute(key, (k, acc) -> {
            if (acc == null) {
                return Ohlc1mAccumulator.first(
                        event.price(),
                        volume,
                        event.eventTime()
                );
            }
            acc.apply(event.price(), volume, event.eventTime());
            return acc;
        });
    }

    public Set<AggregateKey> keys() {
        return store.keySet();
    }

    public Ohlc1mAccumulator remove(AggregateKey key) {
        return store.remove(key);
    }

    public int size() {
        return store.size();
    }
}
