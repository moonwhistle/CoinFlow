package com.coinflow.domain.aggregation.domain.vo;

/**
 * Combined data of an interval and its snapshot.
 */
public record ClosedKlineSnapshot(String interval, KlineSnapshot snapshot) {
}
