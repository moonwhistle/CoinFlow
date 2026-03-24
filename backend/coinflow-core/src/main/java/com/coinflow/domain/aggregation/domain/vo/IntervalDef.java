package com.coinflow.aggregation.domain.service.dto;

/**
 * Definition of an aggregation interval.
 * @param name Interval name (e.g. M1, M5, M30)
 * @param seconds Duration in seconds
 */
public record IntervalDef(String name, int seconds) {
}
