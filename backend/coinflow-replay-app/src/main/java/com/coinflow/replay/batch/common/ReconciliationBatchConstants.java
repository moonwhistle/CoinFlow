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

    // Default Values
    public static final String DEFAULT_SYMBOL = "BTCUSDT";
    public static final String DEFAULT_INTERVAL = "1m";

    // Time Constants (ms)
    public static final long ONE_MINUTE_MS = 60_000L;
    public static final long FIVE_MINUTES_MS = 300_000L;
}
