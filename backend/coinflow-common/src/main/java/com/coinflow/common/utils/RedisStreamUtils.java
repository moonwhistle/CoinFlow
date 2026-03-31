package com.coinflow.common.utils;

import org.springframework.data.redis.connection.stream.RecordId;

/**
 * Utility for Redis Steam operations.
 * Implements comparison logic for RecordId which may not be Comparable in some versions.
 */
public class RedisStreamUtils {

    /**
     * Compares two RecordIds.
     * RecordId format: "<millis>-<sequence>"
     * @return 0 if equal, -1 if id1 < id2, 1 if id1 > id2
     */
    public static int compare(RecordId id1, RecordId id2) {
        if (id1 == id2) return 0;
        if (id1 == null) return -1;
        if (id2 == null) return 1;

        String[] p1 = id1.getValue().split("-");
        String[] p2 = id2.getValue().split("-");

        long t1 = Long.parseLong(p1[0]);
        long t2 = Long.parseLong(p2[0]);

        if (t1 != t2) {
            return Long.compare(t1, t2);
        }

        long s1 = Long.parseLong(p1[1]);
        long s2 = Long.parseLong(p2[1]);

        return Long.compare(s1, s2);
    }
    
    private RedisStreamUtils() {}
}
