package com.coinflow.replay.batch.scheduler;

import com.coinflow.replay.batch.config.ReconciliationJobConfig;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

@Slf4j
@Component
@RequiredArgsConstructor
public class ReconciliationScheduler {

    private final JobLauncher jobLauncher;
    private final ReconciliationJobConfig jobConfig; // To get the Job bean

    // Initially for BTCUSDT, can be expanded to a list of symbols
    private static final String DEFAULT_SYMBOL = "BTCUSDT";
    private static final String DEFAULT_INTERVAL = "1m";

    /**
     * Run reconciliation every 5 minutes.
     * Uses a fixed delay to prevent overlapping runs if one takes longer than 5
     * mins.
     */
    @Scheduled(fixedDelayString = "${coinflow.batch.reconciliation.interval:300000}", initialDelay = 10000)
    public void runReconciliation() {
        log.info("Starting scheduled kline reconciliation for {}", DEFAULT_SYMBOL);

        try {
            // Calculate time window:
            // endTime: Current time - 1 minute (rounded down to nearest minute to ensure
            // candle is closed)
            // startTime: endTime - 5 minutes
            long nowMs = Instant.now().toEpochMilli();
            long endTime = (nowMs / 60000) * 60000 - 60000; // Last closed minute
            long startTime = endTime - (5 * 60000); // 5 minutes window

            JobParameters params = new JobParametersBuilder()
                    .addString("symbol", DEFAULT_SYMBOL)
                    .addString("interval", DEFAULT_INTERVAL)
                    .addLong("startTime", startTime)
                    .addLong("endTime", endTime)
                    .addLong("run.id", nowMs) // Ensure uniqueness
                    .toJobParameters();

            jobLauncher.run(jobConfig.klineReconciliationJob(null, null), params); // JobRepository/Step are injected by
                                                                                   // Spring

            log.info("Scheduled kline reconciliation triggered successfully for {}. Range: {} to {}",
                    DEFAULT_SYMBOL, startTime, endTime);

        } catch (Exception e) {
            log.error("Failed to trigger scheduled kline reconciliation job", e);
        }
    }
}
