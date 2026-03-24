package com.coinflow.aggregation.domain.service.dto;

import com.coinflow.aggregation.domain.model.dto.ClosedKlineSnapshot;
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
