package com.coinflow.domain.ohlc.service;

import com.coinflow.domain.ohlc.domain.Ohlc5m;
import com.coinflow.domain.ohlc.repository.Ohlc5mRepository;
import com.coinflow.domain.symbol.domain.Symbol;
import com.coinflow.process.rollup.OhlcRollup;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class Ohlc5mService {

    private final Ohlc5mRepository ohlc5mRepository;

    @Transactional
    public void upsert(Long symbolId, Symbol symbol, LocalDateTime bucketTime, OhlcRollup rollup) {
        Ohlc5m candle = ohlc5mRepository.findBySymbolIdAndBucketTime(symbolId, bucketTime)
                .orElseGet(() -> Ohlc5m.builder()
                        .symbol(symbol)
                        .bucketTime(bucketTime)
                        .build());

        candle.apply(
                rollup.open(),
                rollup.high(),
                rollup.low(),
                rollup.close(),
                rollup.volume()
        );

        ohlc5mRepository.save(candle);
    }
}
