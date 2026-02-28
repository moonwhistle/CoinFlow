package com.coinflow.domain.ohlc.service;

import com.coinflow.domain.ohlc.domain.Ohlc5m;
import com.coinflow.domain.ohlc.repository.Ohlc5mRepository;
import com.coinflow.domain.symbol.domain.Symbol;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class Ohlc5mService {

    private final Ohlc5mRepository ohlc5mRepository;

    @Transactional
    public void applyAndSave(Symbol symbol, LocalDateTime bucketTime, BigDecimal open, BigDecimal high,
            BigDecimal low, BigDecimal close, long volume) {
        Long symbolId = symbol.getId();
        Ohlc5m candle = ohlc5mRepository.findBySymbolIdAndBucketTime(symbolId, bucketTime)
                .orElseGet(() -> Ohlc5m.builder()
                        .symbol(symbol)
                        .bucketTime(bucketTime)
                        .build());

        candle.apply(
                open,
                high,
                low,
                close,
                volume);

        ohlc5mRepository.save(candle);
    }

    @Transactional(readOnly = true)
    public List<Ohlc5m> findCandlesInBucketRange(Long symbolId, LocalDateTime startInclusive,
            LocalDateTime endExclusive) {
        return ohlc5mRepository.findCandlesInBucketRange(symbolId, startInclusive, endExclusive);
    }
}
