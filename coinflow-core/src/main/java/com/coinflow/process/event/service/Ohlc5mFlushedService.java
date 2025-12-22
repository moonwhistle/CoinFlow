package com.coinflow.process.event.service;

import com.coinflow.domain.ohlc.constant.OhlcInterval;
import com.coinflow.process.event.Ohlc1mFlushedEvent;
import com.coinflow.process.service.Ohlc5mRollupService;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class Ohlc5mFlushedService implements FlushedService{

    private static final OhlcInterval TARGET_INTERVAL = OhlcInterval.M5;

    private final Ohlc5mRollupService rollupService;

    @Override
    public void onOhlc1mFlushed(Ohlc1mFlushedEvent event) {
        LocalDateTime bucketStart1m = event.bucketStart1m();
        Long symbolId = event.symbolId();
        LocalDateTime bucketStart5m = TARGET_INTERVAL.resolveBucketStart(bucketStart1m);
        rollupService.rollupIfClosed(symbolId, bucketStart5m);

        rollupService.rollupIfClosed(
                symbolId,
                bucketStart5m.minus(TARGET_INTERVAL.duration())
        );
    }
}
