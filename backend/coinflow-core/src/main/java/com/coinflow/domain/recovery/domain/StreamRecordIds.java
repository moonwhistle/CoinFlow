package com.coinflow.domain.recovery.domain;

/** Redis stream IDs are numeric pairs, not lexicographically ordered strings. */
public final class StreamRecordIds {
    private StreamRecordIds() {}

    public static int compare(String left, String right) {
        String[] a = left.split("-", 2);
        String[] b = right.split("-", 2);
        int timestamp = Long.compareUnsigned(Long.parseUnsignedLong(a[0]), Long.parseUnsignedLong(b[0]));
        return timestamp != 0 ? timestamp
                : Long.compareUnsigned(Long.parseUnsignedLong(a[1]), Long.parseUnsignedLong(b[1]));
    }
}
