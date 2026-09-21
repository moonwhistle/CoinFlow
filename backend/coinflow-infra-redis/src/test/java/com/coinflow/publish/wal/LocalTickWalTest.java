package com.coinflow.publish.wal;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LocalTickWalTest {

    @TempDir
    Path tempDir;

    @Test
    void reopensFromCheckpointAndDeletesFullyCommittedSegments() throws Exception {
        byte[] payload = new byte[20];
        try (LocalTickWal wal = new LocalTickWal(tempDir, 50, 500)) {
            WalRecord first = wal.append(payload);
            WalRecord second = wal.append(payload);
            WalRecord third = wal.append(payload);

            wal.commit(second);

            assertThat(wal.checkpoint().sequence()).isEqualTo(second.sequence());
            assertThat(wal.readPending(10)).extracting(WalRecord::sequence).containsExactly(third.sequence());
            assertThat(wal.segmentCount()).isEqualTo(1);
            assertThat(first.segmentId()).isLessThan(third.segmentId());
        }

        try (LocalTickWal reopened = new LocalTickWal(tempDir, 50, 500)) {
            assertThat(reopened.checkpoint().sequence()).isEqualTo(1L);
            assertThat(reopened.readPending(10)).extracting(WalRecord::sequence).containsExactly(2L);
        }
    }

    @Test
    void truncatesAnIncompleteTailRecordOnRecovery() throws Exception {
        long validSize;
        try (LocalTickWal wal = new LocalTickWal(tempDir, 1024, 4096)) {
            wal.append(new byte[] {1, 2, 3, 4});
            validSize = wal.totalBytes();
        }
        Path segment = firstSegment();
        Files.write(segment, new byte[] {0, 0, 0, 10, 1, 2}, StandardOpenOption.APPEND);

        try (LocalTickWal reopened = new LocalTickWal(tempDir, 1024, 4096)) {
            assertThat(reopened.totalBytes()).isEqualTo(validSize);
            assertThat(reopened.readPending(10)).hasSize(1);
        }
    }

    @Test
    void blocksAtCapacityUntilCommittedSegmentIsReclaimed() throws Exception {
        byte[] payload = new byte[20];
        try (LocalTickWal wal = new LocalTickWal(tempDir, 50, 72)) {
            WalRecord first = wal.append(payload);
            wal.append(payload);

            CompletableFuture<WalRecord> blocked = CompletableFuture.supplyAsync(() -> {
                try {
                    return wal.append(payload);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });
            Thread.sleep(100);
            assertThat(blocked).isNotDone();
            assertThat(wal.isAtCapacity()).isTrue();

            wal.commit(first);

            assertThat(blocked.get(2, TimeUnit.SECONDS)).isNotNull();
        }
    }

    @Test
    void checkpointNeverAdvancesPastExplicitlyCommittedRecord() throws Exception {
        try (LocalTickWal wal = new LocalTickWal(tempDir, 1024, 4096)) {
            List<WalRecord> records = List.of(
                    wal.append(new byte[] {1}),
                    wal.append(new byte[] {2}),
                    wal.append(new byte[] {3}));

            wal.commit(records.get(1));

            assertThat(wal.checkpoint().sequence()).isEqualTo(1L);
            assertThat(wal.readPending(10)).extracting(WalRecord::sequence).containsExactly(2L);
        }
    }

    @Test
    void discardsTheLastRecordWhenItsCrcIsCorrupt() throws Exception {
        try (LocalTickWal wal = new LocalTickWal(tempDir, 1024, 4096)) {
            wal.append(new byte[] {1, 2, 3, 4});
        }
        Path segment = firstSegment();
        try (FileChannel channel = FileChannel.open(segment, StandardOpenOption.WRITE)) {
            channel.write(ByteBuffer.wrap(new byte[] {99}), channel.size() - 1);
        }

        try (LocalTickWal reopened = new LocalTickWal(tempDir, 1024, 4096)) {
            assertThat(reopened.readPending(10)).isEmpty();
            assertThat(reopened.totalBytes()).isZero();
        }
    }

    @Test
    void rejectsACheckpointWithAnInvalidChecksum() throws Exception {
        try (LocalTickWal wal = new LocalTickWal(tempDir, 1024, 4096)) {
            WalRecord record = wal.append(new byte[] {1, 2, 3});
            wal.commit(record);
        }
        Path checkpoint = tempDir.resolve("checkpoint.dat");
        try (FileChannel channel = FileChannel.open(checkpoint, StandardOpenOption.WRITE)) {
            channel.write(ByteBuffer.wrap(new byte[] {99}), 0);
        }

        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> new LocalTickWal(tempDir, 1024, 4096))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("checksum");
    }

    private Path firstSegment() throws IOException {
        try (Stream<Path> files = Files.list(tempDir)) {
            return files.filter(path -> path.getFileName().toString().endsWith(".log"))
                    .findFirst().orElseThrow();
        }
    }
}
