package com.coinflow.aggregation.process.provider;

import com.coinflow.aggregation.process.aggregate.AggregateKey;
import com.coinflow.aggregation.process.aggregate.OhlcAccumulator;
import com.coinflow.aggregation.process.store.Ohlc1mAggregationStore;
import com.coinflow.domain.ohlc.domain.Ohlc1m;
import com.coinflow.domain.ohlc.provider.RealTimeOhlcProvider;
import com.coinflow.domain.symbol.domain.Symbol;
import com.coinflow.domain.symbol.service.SymbolService;
import java.time.LocalDateTime;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class RealTimeOhlcAggregationProvider implements RealTimeOhlcProvider {

    private final Ohlc1mAggregationStore aggregationStore;
    private final SymbolService symbolService;

    @Override
    public Optional<Ohlc1m> getRealTimeCandle(Long symbolId, LocalDateTime bucketTime) {
        AggregateKey key = new AggregateKey(symbolId, bucketTime);
        OhlcAccumulator accumulator = aggregationStore.peek(key);

        if (accumulator == null) {
            return Optional.empty();
        }

        // Symbol 정보 조회를 최소화하기 위해, 여기서는 Symbol 객체가 필요한 경우 Repository 등을 통해 가져옴
        // 하지만 Ohlc1m 객체 생성 시 Symbol 전체가 필요하다면 조회해야 함.
        // 성능 최적화를 위해 Symbol을 단순 참조만 하거나, 캐시를 쓴다고 가정.
        Symbol symbol = symbolService.findSymbol(symbolId);

        return Optional.of(Ohlc1m.builder()
                .symbol(symbol)
                .bucketTime(bucketTime)
                .open(accumulator.getOpen())
                .high(accumulator.getHigh())
                .low(accumulator.getLow())
                .close(accumulator.getClose())
                .volume(accumulator.getVolume())
                .build());
    }
}
