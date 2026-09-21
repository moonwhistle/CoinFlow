package com.coinflow.replay.batch.writer;

import com.coinflow.domain.ohlc.domain.AbstractOhlc;
import com.coinflow.domain.recovery.service.RecoveryCandleStore;
import lombok.RequiredArgsConstructor;
import org.springframework.batch.item.Chunk;
import org.springframework.batch.item.ItemWriter;
import org.springframework.lang.NonNull;

@RequiredArgsConstructor
public class OhlcRollupWriter implements ItemWriter<AbstractOhlc> {
    private final RecoveryCandleStore candles;
    @Override
    public void write(@NonNull Chunk<? extends AbstractOhlc> chunk) {
        chunk.forEach(candles::saveVerified);
    }
}
