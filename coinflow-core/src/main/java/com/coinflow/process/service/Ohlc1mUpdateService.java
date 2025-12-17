package com.coinflow.process.service;

import com.coinflow.domain.ohlc.domain.Ohlc1m;
import com.coinflow.domain.ohlc.service.Ohlc1mService;
import com.coinflow.domain.symbol.domain.Symbol;
import com.coinflow.domain.tick.event.TickRawEvent;
import com.coinflow.process.policy.VolumeScaler;
import com.coinflow.process.util.TimeBucket;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class Ohlc1mUpdateService {

    private final Ohlc1mService ohlc1mService;

    @Transactional
    public Ohlc1m update(Symbol symbol, TickRawEvent event) {
        LocalDateTime bucketStartUtc = TimeBucket.to1m(event.eventTime());
        Ohlc1m candle = ohlc1mService.findOrCreateForUpdate(symbol, bucketStartUtc);
        long volume = VolumeScaler.toLong(event.quantity());
        candle.applyTick(event.price(), volume);

        return ohlc1mService.save(candle);
    }
}
