package com.coinflow.domain.recovery.repository;

import java.util.List;

/** Redis recovery operations, without exposing a Redis client to domain services. */
public interface RecoveryStreamRepository {
    List<String> pending(String stream, String group, String afterExclusive, String throughInclusive, int count);
    void acknowledge(String stream, String group, List<String> ids);
    boolean hasSingleGroup(String stream);
    void trimBefore(String stream, String floor);
    void clearLive(String symbol, String interval, long bucket);
}
