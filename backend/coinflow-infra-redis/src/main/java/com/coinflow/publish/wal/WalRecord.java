package com.coinflow.publish.wal;

public record WalRecord(
        long segmentId,
        long startOffset,
        long endOffset,
        long sequence,
        byte[] payload
) {}
