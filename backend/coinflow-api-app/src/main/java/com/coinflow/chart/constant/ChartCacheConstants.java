package com.coinflow.chart.constant;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;

/**
 * Shared constants for the chart caching system.
 * Adheres to SRP and DRY by centralizing configuration metadata.
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class ChartCacheConstants {

    /**
     * The sliding window size for the global Redis ZSET cache.
     */
    public static final int MAX_HOT_WINDOW_SIZE = 1000;

    /**
     * Redis key prefix for OHLC candle windows.
     */
    public static final String REDIS_WINDOW_KEY_PREFIX = "klines:window:";

    /**
     * Lock key prefix for preventing Thundering Herd during cache backfill.
     */
    public static final String LOCK_KEY_PREFIX = "lock:klines:";

    /**
     * Timeout for acquiring the local mutex lock in seconds.
     */
    public static final long LOCK_TIMEOUT_SECONDS = 3;

    /**
     * Property name to enable/disable chart cache warm-up at startup.
     */
    public static final String WARMUP_ENABLED_PROPERTY = "chart.cache.warmup.enabled";
}
