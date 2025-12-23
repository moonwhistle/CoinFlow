package com.coinflow.domain.ohlc.service;

import com.coinflow.domain.ohlc.domain.Ohlc1m;
import com.coinflow.domain.ohlc.repository.Ohlc1mRepository;
import com.coinflow.domain.symbol.domain.Symbol;
import com.coinflow.process.aggregate.OhlcAccumulator;
import java.time.LocalDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class Ohlc1mService {

    private final Ohlc1mRepository ohlc1mRepository;

    @Transactional
    public void applyAndSave(Symbol symbol, LocalDateTime bucketTime, OhlcAccumulator acc) {
        Ohlc1m candle = ohlc1mRepository.findBySymbolIdAndBucketTime(symbol.getId(), bucketTime)
                .orElseGet(() -> Ohlc1m.builder()
                        .symbol(symbol)
                        .bucketTime(bucketTime)
                        .build()
                );

        candle.apply(
                acc.getOpen(),
                acc.getHigh(),
                acc.getLow(),
                acc.getClose(),
                acc.getVolume()
        );

        ohlc1mRepository.save(candle);
    }

    @Transactional(readOnly = true)
    public List<Ohlc1m> findCandlesInBucketRange(Long symbolId, LocalDateTime startInclusive, LocalDateTime endExclusive) {
        return ohlc1mRepository.findCandlesInBucketRange(symbolId, startInclusive, endExclusive);
    }
}
