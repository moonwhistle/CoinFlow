package com.coinflow.replay.batch.model;

import com.coinflow.domain.symbol.domain.Symbol;
import lombok.Value;

import java.time.LocalDateTime;

/**
 * Represents a higher timeframe bucket that needs recalculation.
 */
@Value
public class RollupTarget {
    Symbol symbol;
    LocalDateTime bucketTime;
    int intervalMinutes;
}
