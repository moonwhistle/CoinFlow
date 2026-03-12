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
    public static final String CONTEXT_DIRTY_BUCKETS = "dirtyBuckets";

    // Step/Bean Names
    public static final String JOB_NAME = "klineReconciliationJob";
    public static final String MANAGER_STEP_NAME = "managerStep";
    public static final String WORKER_STEP_NAME = "klineReconciliationStep";
    public static final String WORKER_STEP_SINGLE_NAME = "workerStep";
    public static final String WORKER_FLOW_NAME = "workerFlow";
    public static final String ROLLUP_5M_STEP_NAME = "ohlc5mRollupStep";
    public static final String ROLLUP_30M_STEP_NAME = "ohlc30mRollupStep";
    public static final String TASK_EXECUTOR_BEAN = "batchTaskExecutor";

    // Intervals
    public static final String INTERVAL_1M = "1m";
    public static final String INTERVAL_5M = "5m";
    public static final String INTERVAL_30M = "30m";
    public static final int INTERVAL_5M_MINUTES = 5;
    public static final int INTERVAL_30M_MINUTES = 30;

    // Default Values
    public static final String DEFAULT_SYMBOL = "btcusdt";
    public static final String DEFAULT_INTERVAL = "1m";
    public static final int DEFAULT_WINDOW_MINUTES = 120;

    // Time Constants (ms)
    public static final long ONE_MINUTE_MS = 60_000L;
    public static final long FIVE_MINUTES_MS = 300_000L;
    public static final long INITIAL_DELAY_MS = 10_000L;

    // Partitioning & Parallelism
    public static final int DEFAULT_GRID_SIZE = 5;
    public static final int DEFAULT_THREAD_POOL_SIZE = 10;
    public static final String BATCH_THREAD_PREFIX = "batch-thread-";
    public static final int CHUNK_SIZE = 500;
}
