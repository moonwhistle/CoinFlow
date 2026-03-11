package com.coinflow.replay.batch.scheduler;

import com.coinflow.replay.batch.common.ReconciliationBatchConstants;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class ReconciliationScheduler {

    private final JobLauncher jobLauncher;
    private final Job klineReconciliationJob;

    @Value("${coinflow.batch.reconciliation.symbols:btcusdt}")
    private List<String> targetSymbols;

    @Value("${coinflow.batch.reconciliation.default-interval:1m}")
    private String defaultInterval;

    @Value("${coinflow.batch.reconciliation.window-minutes:5}")
    private int windowMinutes;

    /**
     * Run reconciliation periodically for all configured symbols.
     * Uses a fixed delay to prevent overlapping runs.
     */
    @Scheduled(fixedDelayString = "${coinflow.batch.reconciliation.interval:300000}", initialDelay = 10000)
    public void runReconciliation() {
        if (targetSymbols == null || targetSymbols.isEmpty()) {
            log.warn("No target symbols configured for reconciliation.");
            return;
        }

        log.info("Starting scheduled kline reconciliation for symbols: {}", targetSymbols);

        long nowMs = Instant.now().toEpochMilli();
        long endTime = (nowMs / ReconciliationBatchConstants.ONE_MINUTE_MS) * ReconciliationBatchConstants.ONE_MINUTE_MS
                - ReconciliationBatchConstants.ONE_MINUTE_MS;
        long startTime = endTime - ((long) windowMinutes * ReconciliationBatchConstants.ONE_MINUTE_MS);

        try {
            JobParameters params = new JobParametersBuilder()
                    .addString(ReconciliationBatchConstants.PARAM_INTERVAL,
                            defaultInterval != null ? defaultInterval : ReconciliationBatchConstants.DEFAULT_INTERVAL)
                    .addLong(ReconciliationBatchConstants.PARAM_START_TIME, startTime)
                    .addLong(ReconciliationBatchConstants.PARAM_END_TIME, endTime)
                    .addLong(ReconciliationBatchConstants.PARAM_RUN_ID, nowMs)
                    .toJobParameters();

            jobLauncher.run(klineReconciliationJob, params);
            log.info("Successfully triggered reconciliation job. Range: {} to {}", startTime, endTime);
        } catch (Exception e) {
            log.error("Failed to trigger reconciliation job", e);
        }
    }
}
