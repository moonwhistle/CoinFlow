package com.coinflow.process.event.service;
import com.coinflow.process.event.Ohlc1mFlushedEvent;
import com.coinflow.process.service.Ohlc5mRollupService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class Ohlc5mFlushedService implements FlushedService{

    private final Ohlc5mRollupService rollupService;

    @Override
    public void onOhlc1mFlushed(Ohlc1mFlushedEvent event) {
        rollupService.rollupInNewTransaction(
                event.symbolId(),
                event.bucketStart1m()
        );
    }
}
