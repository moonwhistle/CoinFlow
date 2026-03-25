package com.coinflow.domain.aggregation.domain.vo;

import java.util.List;

/**
 * Result of a single tick processing.
 */
public record AggregationResult(
        List<ClosedKlineSnapshot> closedSnapshots,
        List<ClosedKlineSnapshot> liveSnapshots,
        List<ClosedKlineSnapshot> lateUpdatedSnapshots
) {
}
