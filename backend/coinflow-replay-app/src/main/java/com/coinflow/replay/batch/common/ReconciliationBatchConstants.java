package com.coinflow.replay.batch.common;

/**
 * Constants used throughout the reconciliation batch process.
 * Prevents Magic Strings and ensures consistency between Scheduler, Reader, and
 * Processor.
 */
public final class ReconciliationBatchConstants {

    private ReconciliationBatchConstants() {
    }

    // Job Parameter Keys
    public static final String PARAM_SYMBOL = "symbol";
    public static final String PARAM_INTERVAL = "interval";
    public static final String PARAM_START_TIME = "startTime";
    public static final String PARAM_END_TIME = "endTime";
    public static final String PARAM_RUN_ID = "run.id";

    // Step/Bean Names
    public static final String JOB_NAME = "klineReconciliationJob";
    public static final String MANAGER_STEP_NAME = "managerStep";
    public static final String WORKER_STEP_NAME = "klineReconciliationStep";
    public static final String TASK_EXECUTOR_BEAN = "batchTaskExecutor";

    // Default Values
    public static final String DEFAULT_SYMBOL = "btcusdt";
    public static final String DEFAULT_INTERVAL = "1m";
    public static final int DEFAULT_WINDOW_MINUTES = 5;

    // Time Constants (ms)
    public static final long ONE_MINUTE_MS = 60_000L;
    public static final long FIVE_MINUTES_MS = 300_000L;
    public static final long INITIAL_DELAY_MS = 10_000L;

    // Partitioning & Parallelism
    public static final int DEFAULT_GRID_SIZE = 5;
    public static final int DEFAULT_THREAD_POOL_SIZE = 10;
}
