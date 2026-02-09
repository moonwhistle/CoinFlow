package com.coinflow.aggregation.service.flush;

import com.coinflow.aggregation.service.event.CandleClosedEventPublisher;
import com.coinflow.domain.ohlc.service.Ohlc1mService;
import com.coinflow.domain.symbol.domain.Symbol;
import com.coinflow.domain.symbol.service.SymbolService;
import com.coinflow.aggregation.process.aggregate.AggregateKey;
import com.coinflow.aggregation.process.aggregate.OhlcAccumulator;
import com.coinflow.aggregation.process.policy.VolumeScaler;
import com.coinflow.aggregation.event.Ohlc1mFlushedEvent;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class Ohlc1mFlushService {

    private final Ohlc1mService ohlc1mService;
    private final SymbolService symbolService;
    private final CandleClosedEventPublisher eventPublisher;
    private final ApplicationEventPublisher applicationEventPublisher;

    @Transactional
    public void flush(AggregateKey key, OhlcAccumulator acc) {
        Symbol symbol = symbolService.findSymbol(key.symbolId());
        ohlc1mService.applyAndSave(symbol, key.bucket(), acc.getOpen(), acc.getHigh(), acc.getLow(), acc.getClose(),
                acc.getVolume());

        // 1. WebSocket/Redis Pub (Frontend)
        eventPublisher.publish(
                symbol.getId(),
                symbol.getSymbol(),
                "M1",
                key.bucket().toString(),
                acc.getOpen(),
                acc.getHigh(),
                acc.getLow(),
                acc.getClose(),
                VolumeScaler.toBigDecimal(acc.getVolume()));

        // 2. Internal Event Pub (Rollup Trigger)
        applicationEventPublisher.publishEvent(new Ohlc1mFlushedEvent(
                symbol.getId(),
                key.bucket(),
                java.time.Instant.now()));
    }
}
