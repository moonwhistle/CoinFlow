package com.coinflow.publish.wal;

public record WalCheckpoint(long segmentId, long offset, long sequence) {
    public static WalCheckpoint initial(long segmentId) {
        return new WalCheckpoint(segmentId, 0L, -1L);
    }
}
