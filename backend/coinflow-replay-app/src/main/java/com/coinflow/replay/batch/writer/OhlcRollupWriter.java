package com.coinflow.replay.batch.writer;

import com.coinflow.domain.ohlc.domain.AbstractOhlc;
import com.coinflow.domain.ohlc.domain.Ohlc30m;
import com.coinflow.domain.ohlc.domain.Ohlc5m;
import com.coinflow.domain.ohlc.service.Ohlc30mService;
import com.coinflow.domain.ohlc.service.Ohlc5mService;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.item.Chunk;
import org.springframework.batch.item.ItemWriter;
import org.springframework.lang.NonNull;

import java.util.ArrayList;
import java.util.List;

@RequiredArgsConstructor
public class OhlcRollupWriter implements ItemWriter<AbstractOhlc> {
    private static final Logger log = LoggerFactory.getLogger(OhlcRollupWriter.class);

    private final Ohlc5mService ohlc5mService;
    private final Ohlc30mService ohlc30mService;

    @Override
    public void write(@NonNull Chunk<? extends AbstractOhlc> chunk) {
        List<Ohlc5m> ohlc5mList = new ArrayList<>();
        List<Ohlc30m> ohlc30mList = new ArrayList<>();

        for (AbstractOhlc item : chunk) {
            if (item instanceof Ohlc5m) {
                ohlc5mList.add((Ohlc5m) item);
            } else if (item instanceof Ohlc30m) {
                ohlc30mList.add((Ohlc30m) item);
            }
        }

        if (!ohlc5mList.isEmpty()) {
            log.info("Saving {} updated Ohlc5m candles", ohlc5mList.size());
            ohlc5mService.saveAll(ohlc5mList);
        }

        if (!ohlc30mList.isEmpty()) {
            log.info("Saving {} updated Ohlc30m candles", ohlc30mList.size());
            ohlc30mService.saveAll(ohlc30mList);
        }
    }
}
