package com.coinflow.domain.ohlc.service;

import com.coinflow.common.exception.CoreErrorCode;
import com.coinflow.common.exception.CoreException;
import com.coinflow.domain.ohlc.domain.Ohlc1m;
import com.coinflow.domain.ohlc.repository.Ohlc1mRepository;
import com.coinflow.domain.symbol.domain.Symbol;
import com.coinflow.process.aggregate.OhlcAccumulator;
import java.time.LocalDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class Ohlc1mService {

    private final Ohlc1mRepository ohlc1mRepository;

    @Transactional
    public void applyAndSave(Symbol symbol, LocalDateTime bucketTime, OhlcAccumulator acc) {
        Ohlc1m saved = Ohlc1m.builder()
                .symbol(symbol)
                .bucketTime(bucketTime)
                .build();

        saved.apply(
                acc.getOpen(),
                acc.getHigh(),
                acc.getLow(),
                acc.getClose(),
                acc.getVolume()
        );

        try {
            ohlc1mRepository.save(saved);
        } catch (DataIntegrityViolationException e) {
            throw new CoreException(CoreErrorCode.DUPLICATE_OHLC_1M);
        }
    }

    @Transactional(readOnly = true)
    public List<Ohlc1m> findCandlesInBucketRange(Long symbolId, LocalDateTime startInclusive, LocalDateTime endExclusive) {
        return ohlc1mRepository.findCandlesInBucketRange(symbolId, startInclusive, endExclusive);
    }
}
