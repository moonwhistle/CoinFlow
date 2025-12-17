package com.coinflow.domain.ohlc.service;

import com.coinflow.domain.ohlc.domain.Ohlc1m;
import com.coinflow.domain.ohlc.repository.Ohlc1mRepository;
import com.coinflow.domain.symbol.domain.Symbol;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class Ohlc1mService {

    private final Ohlc1mRepository ohlc1mRepository;

    @Transactional
    public Ohlc1m findOrCreateForUpdate(Symbol symbol, LocalDateTime bucketTime) {
        return ohlc1mRepository.findForUpdate(symbol, bucketTime)
                .orElseGet(() -> Ohlc1m.builder()
                        .symbol(symbol)
                        .bucketTime(bucketTime)
                        .build());
    }

    @Transactional
    public Ohlc1m save(Ohlc1m ohlc1m) {
        return ohlc1mRepository.save(ohlc1m);
    }
}
