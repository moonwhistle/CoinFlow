package com.coinflow.domain.ohlc.service;

import com.coinflow.domain.ohlc.domain.Ohlc1m;
import com.coinflow.domain.ohlc.repository.Ohlc1mRepository;
import com.coinflow.domain.symbol.domain.Symbol;
import java.math.BigDecimal;
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
    public void applyAndSave(Symbol symbol, LocalDateTime bucketTime, BigDecimal open, BigDecimal high, BigDecimal low,
                             BigDecimal close, long volume) {
        Ohlc1m candle = ohlc1mRepository.findBySymbolIdAndBucketTime(symbol.getId(), bucketTime)
                .orElseGet(() -> Ohlc1m.builder()
                        .symbol(symbol)
                        .bucketTime(bucketTime)
                        .build()
                );

        candle.apply(
                open,
                high,
                low,
                close,
                volume
        );

        ohlc1mRepository.save(candle);
    }

    @Transactional(readOnly = true)
    public List<Ohlc1m> findCandlesInBucketRange(Long symbolId, LocalDateTime startInclusive,
                                                 LocalDateTime endExclusive) {
        return ohlc1mRepository.findCandlesInBucketRange(symbolId, startInclusive, endExclusive);
    }
}
