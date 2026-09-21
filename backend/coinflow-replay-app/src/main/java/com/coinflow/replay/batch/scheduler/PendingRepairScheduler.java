package com.coinflow.replay.batch.scheduler;

import com.coinflow.domain.recovery.domain.FailedRecord;
import com.coinflow.domain.recovery.repository.FailedRecordRepository;
import com.coinflow.domain.recovery.repository.VerifiedCandleRepository;
import com.coinflow.replay.batch.common.ReconciliationBatchConstants;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Repair old failed buckets that have fallen outside the rolling reconciliation window. */
@Component
@RequiredArgsConstructor
@Slf4j
public class PendingRepairScheduler {
    private final FailedRecordRepository failures;
    private final VerifiedCandleRepository verified;
    private final JobLauncher launcher;
    private final Job klineReconciliationJob;
    @Value("${coinflow.batch.reconciliation.symbols:btcusdt}") private List<String> symbols;
    @Value("${coinflow.batch.reconciliation.window-minutes:120}") private int windowMinutes;
    private String cursor = "";

    @Scheduled(fixedDelayString = "${coinflow.batch.reconciliation.interval:300000}", initialDelay = 60000)
    public void repairBacklog() {
        var page = failures.findByStatusNotAndIdGreaterThanOrderByIdAsc(
                FailedRecord.Status.RESOLVED, cursor, PageRequest.of(0, 100));
        long recentBoundary = System.currentTimeMillis() / 1000 - windowMinutes * 60L;
        for (var failure : page) {
            cursor = failure.getId();
            if (!symbols.contains(failure.getSymbol()) || failure.getRequiredCandles() == null
                    || failure.getRequiredCandles().isBlank()) continue;
            for (String key : failure.getRequiredCandles().split(",")) {
                long bucket = Long.parseLong(key.substring(key.lastIndexOf(':') + 1));
                if (bucket >= recentBoundary || verified.existsById(key)) continue;
                long start = bucket / 1800 * 1_800_000L;
                try {
                    var execution = launcher.run(klineReconciliationJob, new JobParametersBuilder()
                            .addString(ReconciliationBatchConstants.PARAM_INTERVAL, "1m")
                            .addLong(ReconciliationBatchConstants.PARAM_START_TIME, start)
                            .addLong(ReconciliationBatchConstants.PARAM_END_TIME, start + 1_800_000L)
                            .addString("repair.failure", failure.getId())
                            .addLong(ReconciliationBatchConstants.PARAM_RUN_ID, System.currentTimeMillis())
                            .toJobParameters());
                    log.info("Pending repair finished: bucket={}, status={}", key, execution.getStatus());
                } catch (Exception e) { log.error("PENDING_REPAIR_FAILED bucket={}", key, e); }
                return; // At most one 30-minute repair range per run; no hot polling.
            }
        }
        if (page.size() < 100) cursor = "";
    }
}
