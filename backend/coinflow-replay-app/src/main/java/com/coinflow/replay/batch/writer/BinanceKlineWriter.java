package com.coinflow.replay.batch.writer;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.ExitStatus;
import org.springframework.batch.core.StepExecution;
import org.springframework.batch.core.StepExecutionListener;
import org.springframework.batch.item.Chunk;
import org.springframework.batch.item.ItemWriter;
import org.springframework.lang.NonNull;
import org.springframework.lang.Nullable;

import com.coinflow.domain.log.domain.MissingTickLog;
import com.coinflow.domain.log.service.MissingTickLogService;
import com.coinflow.domain.ohlc.domain.Ohlc1m;
import com.coinflow.domain.ohlc.service.Ohlc1mService;
import com.coinflow.replay.batch.common.ReconciliationBatchConstants;
import com.coinflow.replay.batch.processor.ReconciliationResult;

public class BinanceKlineWriter implements ItemWriter<ReconciliationResult>, StepExecutionListener {
    private static final Logger log = LoggerFactory.getLogger(BinanceKlineWriter.class);

    private final Ohlc1mService ohlc1mService;
    private final MissingTickLogService missingTickLogService;
    private final Set<LocalDateTime> dirtyBuckets = new HashSet<>();

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
            // Collect dirty bucket times
            candles.forEach(c -> dirtyBuckets.add(c.getBucketTime()));
        }

        if (!logs.isEmpty()) {
            log.info("Writing a chunk of {} MissingTickLog records to DB", logs.size());
            missingTickLogService.saveAll(logs);
        }
    }

    @Override
    public void beforeStep(@NonNull StepExecution stepExecution) {
        dirtyBuckets.clear();
    }

    @Override
    @Nullable
    public ExitStatus afterStep(@NonNull StepExecution stepExecution) {
        if (!dirtyBuckets.isEmpty()) {
            String dirtyStr = dirtyBuckets.stream()
                    .sorted() // Sort for deterministic results/logging
                    .map(dt -> dt.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME))
                    .collect(Collectors.joining(","));

            stepExecution.getExecutionContext().putString(ReconciliationBatchConstants.CONTEXT_DIRTY_BUCKETS, dirtyStr);
            log.info("{} records {} dirty buckets for subsequent rollup steps",
                    stepExecution.getStepName(), dirtyBuckets.size());
        }
        return null;
    }
}
