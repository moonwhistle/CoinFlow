package com.coinflow.aggregation.domain.model.dto;

/**
 * Combined data of an interval and its snapshot.
 */
public record ClosedKlineSnapshot(String interval, KlineSnapshot snapshot) {
}
