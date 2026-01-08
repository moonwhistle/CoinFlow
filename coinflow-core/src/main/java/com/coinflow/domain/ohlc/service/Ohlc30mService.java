package com.coinflow.domain.ohlc.service;

import com.coinflow.domain.ohlc.domain.Ohlc30m;
import com.coinflow.domain.ohlc.repository.Ohlc30mRepository;
import com.coinflow.domain.symbol.domain.Symbol;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class Ohlc30mService {

    private final Ohlc30mRepository ohlc30mRepository;

    @Transactional
    public void upsert(Long symbolId, Symbol symbol, LocalDateTime bucketTime, BigDecimal open, BigDecimal high,
                       BigDecimal low, BigDecimal close, long volume) {
        Ohlc30m candle = ohlc30mRepository.findBySymbolIdAndBucketTime(symbolId, bucketTime)
                .orElseGet(() -> Ohlc30m.builder()
                        .symbol(symbol)
                        .bucketTime(bucketTime)
                        .build());

        candle.apply(
                open,
                high,
                low,
                close,
                volume
        );

        ohlc30mRepository.save(candle);
    }

    @Transactional(readOnly = true)
    public List<Ohlc30m> findCandlesInBucketRange(Long symbolId, LocalDateTime startInclusive,
                                                  LocalDateTime endExclusive) {
        return ohlc30mRepository.findCandlesInBucketRange(symbolId, startInclusive, endExclusive);
    }
}

