package com.coinflow.replay.batch.writer;

import com.coinflow.domain.ohlc.domain.Ohlc1m;
import com.coinflow.domain.ohlc.service.Ohlc1mService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.item.Chunk;
import org.springframework.batch.item.ItemWriter;

import java.util.ArrayList;

public class BinanceKlineWriter implements ItemWriter<Ohlc1m> {
    private static final Logger log = LoggerFactory.getLogger(BinanceKlineWriter.class);

    private final Ohlc1mService ohlc1mService;

    public BinanceKlineWriter(Ohlc1mService ohlc1mService) {
        this.ohlc1mService = ohlc1mService;
    }

    @Override
    public void write(Chunk<? extends Ohlc1m> chunk) throws Exception {
        if (chunk.isEmpty()) {
            return; // 처리할 데이터가 없으면 진행하지 않음
        }

        log.info("Writing a chunk of {} Ohlc1m records to DB", chunk.size());

        // Chunk의 내용을 ArrayList로 변환하여 core 모듈로 전송
        ohlc1mService.saveAll(new ArrayList<>(chunk.getItems()));
    }
}
