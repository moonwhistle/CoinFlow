package com.coinflow.replay.batch.common;

import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Shared component to track dirty buckets across partitioned steps for the same
 * symbol.
 */
@Component
public class DirtyBucketTracker {
    private final Map<String, Set<LocalDateTime>> dirtyBucketsMap = new ConcurrentHashMap<>();

    public void addDirtyBuckets(String symbol, Set<LocalDateTime> buckets) {
        dirtyBucketsMap.computeIfAbsent(symbol.toLowerCase(), k -> ConcurrentHashMap.newKeySet())
                .addAll(buckets);
    }

    public Set<LocalDateTime> getDirtyBuckets(String symbol) {
        return dirtyBucketsMap.getOrDefault(symbol.toLowerCase(), Collections.emptySet());
    }

    /**
     * Converts 1m dirty buckets to higher timeframe bucket starts.
     */
    public Set<LocalDateTime> getTargetBuckets(String symbol, int intervalMinutes) {
        Set<LocalDateTime> buckets = getDirtyBuckets(symbol);
        if (buckets.isEmpty()) {
            return Collections.emptySet();
        }

        return buckets.stream()
                .map(dt -> truncateToInterval(dt, intervalMinutes))
                .collect(Collectors.toSet());
    }

    public void clear(String symbol) {
        dirtyBucketsMap.remove(symbol.toLowerCase());
    }

    private LocalDateTime truncateToInterval(LocalDateTime dt, int intervalMinutes) {
        int minute = dt.getMinute();
        int truncatedMinute = (minute / intervalMinutes) * intervalMinutes;
        return dt.withMinute(truncatedMinute).withSecond(0).withNano(0);
    }
}
