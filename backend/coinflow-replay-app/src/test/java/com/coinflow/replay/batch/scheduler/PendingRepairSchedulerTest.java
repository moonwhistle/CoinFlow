package com.coinflow.replay.batch.scheduler;

import com.coinflow.domain.recovery.domain.FailedRecord;
import com.coinflow.domain.recovery.repository.FailedRecordRepository;
import com.coinflow.domain.recovery.repository.VerifiedCandleRepository;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.test.util.ReflectionTestUtils;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class PendingRepairSchedulerTest {
    @Test void repairsOneOldRangeAndDoesNotReplayRawTicks() throws Exception {
        var failures = mock(FailedRecordRepository.class);
        var verified = mock(VerifiedCandleRepository.class);
        var launcher = mock(JobLauncher.class);
        var job = mock(Job.class);
        var scheduler = new PendingRepairScheduler(failures, verified, launcher, job);
        ReflectionTestUtils.setField(scheduler, "symbols", List.of("btcusdt"));
        ReflectionTestUtils.setField(scheduler, "windowMinutes", 120);
        FailedRecord failure = new FailedRecord();
        failure.setId("failure"); failure.setSymbol("btcusdt");
        failure.setRequiredCandles("btcusdt:M1:60,btcusdt:M30:0");
        when(failures.findByStatusNotAndIdGreaterThanOrderByIdAsc(any(), anyString(), any())).thenReturn(List.of(failure));
        when(launcher.run(eq(job), any())).thenReturn(new JobExecution(1L));
        scheduler.repairBacklog();
        verify(launcher, times(1)).run(eq(job), argThat(params -> params.getLong("startTime") == 0L
                && params.getLong("endTime") == 1_800_000L));
    }
}
