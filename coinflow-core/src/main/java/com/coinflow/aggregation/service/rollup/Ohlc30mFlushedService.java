package com.coinflow.aggregation.service.rollup;

import com.coinflow.aggregation.event.Ohlc1mFlushedEvent;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class Ohlc30mFlushedService implements FlushedService {

    private final Ohlc30mRollupService rollupService;

    @Override
    public void onOhlc1mFlushed(Ohlc1mFlushedEvent event) {
        rollupService.rollupInNewTransaction(
                event.symbolId(),
                event.bucketStart1m()
        );
    }
}
