package com.coinflow.replay.batch.writer;

import com.coinflow.domain.log.domain.MissingTickLog;
import com.coinflow.domain.log.service.MissingTickLogService;
import com.coinflow.domain.ohlc.domain.Ohlc1m;
import com.coinflow.domain.ohlc.service.Ohlc1mService;
import com.coinflow.replay.batch.processor.ReconciliationResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.item.Chunk;
import org.springframework.batch.item.ItemWriter;
import org.springframework.lang.NonNull;

import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

public class BinanceKlineWriter implements ItemWriter<ReconciliationResult> {
    private static final Logger log = LoggerFactory.getLogger(BinanceKlineWriter.class);

    private final Ohlc1mService ohlc1mService;
    private final MissingTickLogService missingTickLogService;

    public BinanceKlineWriter(Ohlc1mService ohlc1mService, MissingTickLogService missingTickLogService) {
        this.ohlc1mService = ohlc1mService;
        this.missingTickLogService = missingTickLogService;
    }

    @Override
    public void write(@NonNull Chunk<? extends ReconciliationResult> chunk) throws Exception {
        if (chunk.isEmpty()) {
            return;
        }

        List<Ohlc1m> candles = chunk.getItems().stream()
                .map(ReconciliationResult::ohlc1m)
                .filter(Objects::nonNull)
                .collect(Collectors.toList());

        List<MissingTickLog> logs = chunk.getItems().stream()
                .map(ReconciliationResult::missingTickLog)
                .filter(Objects::nonNull)
                .collect(Collectors.toList());

        if (!candles.isEmpty()) {
            log.info("Writing a chunk of {} Ohlc1m records to DB", candles.size());
            ohlc1mService.saveAll(candles);
        }

        if (!logs.isEmpty()) {
            log.info("Writing a chunk of {} MissingTickLog records to DB", logs.size());
            missingTickLogService.saveAll(logs);
        }
    }
}
