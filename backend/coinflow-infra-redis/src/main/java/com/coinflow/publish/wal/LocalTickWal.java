package com.coinflow.publish.wal;

import java.io.EOFException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.zip.CRC32C;

/**
 * Process-crash recovery WAL. Deliberately does not call FileChannel.force/fsync;
 * host, OS and power-loss durability are outside this experiment.
 */
public final class LocalTickWal implements AutoCloseable {

    static final int HEADER_BYTES = Integer.BYTES + Integer.BYTES + Long.BYTES;
    private static final int CHECKPOINT_BYTES = Long.BYTES * 3 + Integer.BYTES;
    private static final int MAX_PAYLOAD_BYTES = 1024 * 1024;
    private static final String CHECKPOINT_FILE = "checkpoint.dat";
    private static final String CHECKPOINT_TEMP_FILE = "checkpoint.tmp";

    private final Path directory;
    private final long segmentBytes;
    private final long maxBytes;
    private final AtomicLong appendBytes = new AtomicLong();

    private FileChannel appendChannel;
    private long activeSegmentId;
    private long activeOffset;
    private long totalBytes;
    private long pendingBytes;
    private long nextSequence;
    private int segmentCount;
    private WalCheckpoint checkpoint;
    private boolean accepting = true;
    private boolean capacityBlocked;

    public LocalTickWal(Path directory, long segmentBytes, long maxBytes) throws IOException {
        this.directory = directory.toAbsolutePath().normalize();
        this.segmentBytes = segmentBytes;
        this.maxBytes = maxBytes;
        if (segmentBytes <= HEADER_BYTES || maxBytes < segmentBytes) {
            throw new IllegalArgumentException("Invalid WAL capacity configuration");
        }
        initialize();
    }

    public synchronized WalRecord append(byte[] payload) throws IOException, InterruptedException {
        if (payload == null || payload.length == 0 || payload.length > MAX_PAYLOAD_BYTES) {
            throw new IllegalArgumentException("Invalid WAL payload size");
        }
        int recordBytes = HEADER_BYTES + payload.length;
        while (accepting && totalBytes + recordBytes > maxBytes) {
            capacityBlocked = true;
            try {
                wait();
            } finally {
                capacityBlocked = false;
            }
        }
        if (!accepting) {
            throw new IllegalStateException("WAL is closed for appends");
        }
        if (activeOffset > 0 && activeOffset + recordBytes > segmentBytes) {
            rotate();
        }

        long sequence = nextSequence++;
        long startOffset = activeOffset;
        ByteBuffer record = ByteBuffer.allocate(recordBytes);
        record.putInt(payload.length);
        record.putInt(checksum(sequence, payload));
        record.putLong(sequence);
        record.put(payload);
        record.flip();
        while (record.hasRemaining()) {
            appendChannel.write(record);
        }
        activeOffset += recordBytes;
        totalBytes += recordBytes;
        pendingBytes += recordBytes;
        appendBytes.addAndGet(recordBytes);
        notifyAll();
        return new WalRecord(activeSegmentId, startOffset, activeOffset, sequence, payload);
    }

    public synchronized List<WalRecord> readPending(int limit) throws IOException {
        if (limit <= 0) {
            throw new IllegalArgumentException("limit must be positive");
        }
        List<WalRecord> result = new ArrayList<>(limit);
        List<Long> segments = segmentIds();
        long startSegment = checkpoint.segmentId();
        long startOffset = checkpoint.offset();
        for (long segmentId : segments) {
            if (segmentId < startSegment || result.size() >= limit) {
                continue;
            }
            long offset = segmentId == startSegment ? startOffset : 0L;
            try (FileChannel channel = FileChannel.open(segmentPath(segmentId), StandardOpenOption.READ)) {
                long size = channel.size();
                while (offset < size && result.size() < limit) {
                    WalRecord record = readRecord(channel, segmentId, offset, size);
                    if (record == null) {
                        break;
                    }
                    result.add(record);
                    offset = record.endOffset();
                }
            }
        }
        return result;
    }

    public synchronized void commit(WalRecord lastRecord) throws IOException {
        if (lastRecord.sequence() <= checkpoint.sequence()) {
            return;
        }
        WalCheckpoint next = new WalCheckpoint(lastRecord.segmentId(), lastRecord.endOffset(), lastRecord.sequence());
        List<Long> segments = segmentIds();
        int currentIndex = segments.indexOf(next.segmentId());
        if (currentIndex >= 0 && currentIndex + 1 < segments.size()
                && next.offset() == Files.size(segmentPath(next.segmentId()))) {
            next = new WalCheckpoint(segments.get(currentIndex + 1), 0L, next.sequence());
        }
        long newlyCommittedBytes = bytesBetween(checkpoint, next, segments);
        writeCheckpoint(next);
        checkpoint = next;
        pendingBytes = Math.max(0, pendingBytes - newlyCommittedBytes);
        cleanupCommittedSegments();
        notifyAll();
    }

    public synchronized WalCheckpoint checkpoint() {
        return checkpoint;
    }

    public synchronized long pendingRecords() {
        return Math.max(0, nextSequence - checkpoint.sequence() - 1);
    }

    public synchronized long totalBytes() {
        return totalBytes;
    }

    public synchronized long pendingBytes() {
        return pendingBytes;
    }

    public long appendBytes() {
        return appendBytes.get();
    }

    public synchronized int segmentCount() {
        return segmentCount;
    }

    public synchronized boolean isAtCapacity() {
        return capacityBlocked || totalBytes >= maxBytes;
    }

    public synchronized void stopAccepting() {
        accepting = false;
        notifyAll();
    }

    @Override
    public synchronized void close() throws IOException {
        stopAccepting();
        if (appendChannel != null) {
            appendChannel.close();
            appendChannel = null;
        }
    }

    private void initialize() throws IOException {
        Files.createDirectories(directory);
        List<Long> segments = segmentIds();
        if (segments.isEmpty()) {
            Files.createFile(segmentPath(0));
            segments = List.of(0L);
        }

        recoverTail(segments);
        segments = segmentIds();
        activeSegmentId = segments.get(segments.size() - 1);
        segmentCount = segments.size();
        activeOffset = Files.size(segmentPath(activeSegmentId));
        totalBytes = 0;
        long lastSequence = -1;
        for (long segmentId : segments) {
            totalBytes += Files.size(segmentPath(segmentId));
            lastSequence = Math.max(lastSequence, lastSequence(segmentId));
        }
        nextSequence = lastSequence + 1;
        checkpoint = readCheckpoint().orElse(WalCheckpoint.initial(segments.get(0)));
        validateCheckpoint(segments);
        pendingBytes = pendingBytesFrom(checkpoint, segments);
        appendChannel = FileChannel.open(segmentPath(activeSegmentId),
                StandardOpenOption.WRITE, StandardOpenOption.CREATE);
        appendChannel.position(activeOffset);
    }

    private void recoverTail(List<Long> segments) throws IOException {
        for (int index = 0; index < segments.size(); index++) {
            long segmentId = segments.get(index);
            Path path = segmentPath(segmentId);
            boolean lastSegment = index == segments.size() - 1;
            try (FileChannel channel = FileChannel.open(path, StandardOpenOption.READ, StandardOpenOption.WRITE)) {
                long size = channel.size();
                long offset = 0;
                while (offset < size) {
                    try {
                        WalRecord record = readRecord(channel, segmentId, offset, size);
                        if (record == null) {
                            if (!lastSegment) {
                                throw new IOException("Corrupt non-tail WAL segment " + segmentId);
                            }
                            channel.truncate(offset);
                            break;
                        }
                        offset = record.endOffset();
                    } catch (EOFException | IllegalArgumentException e) {
                        if (!lastSegment) {
                            throw new IOException("Corrupt non-tail WAL segment " + segmentId, e);
                        }
                        channel.truncate(offset);
                        break;
                    }
                }
            }
        }
    }

    private WalRecord readRecord(FileChannel channel, long segmentId, long offset, long size) throws IOException {
        if (size - offset < HEADER_BYTES) {
            return null;
        }
        ByteBuffer header = ByteBuffer.allocate(HEADER_BYTES);
        readFully(channel, header, offset);
        header.flip();
        int payloadLength = header.getInt();
        int expectedChecksum = header.getInt();
        long sequence = header.getLong();
        if (payloadLength <= 0 || payloadLength > MAX_PAYLOAD_BYTES || size - offset - HEADER_BYTES < payloadLength) {
            return null;
        }
        ByteBuffer payloadBuffer = ByteBuffer.allocate(payloadLength);
        readFully(channel, payloadBuffer, offset + HEADER_BYTES);
        byte[] payload = payloadBuffer.array();
        if (checksum(sequence, payload) != expectedChecksum) {
            return null;
        }
        return new WalRecord(segmentId, offset, offset + HEADER_BYTES + payloadLength, sequence, payload);
    }

    private void rotate() throws IOException {
        appendChannel.close();
        activeSegmentId++;
        activeOffset = 0;
        appendChannel = FileChannel.open(segmentPath(activeSegmentId),
                StandardOpenOption.WRITE, StandardOpenOption.CREATE_NEW);
        segmentCount++;
    }

    private void cleanupCommittedSegments() throws IOException {
        for (long segmentId : segmentIds()) {
            if (segmentId < checkpoint.segmentId() && segmentId != activeSegmentId) {
                Path path = segmentPath(segmentId);
                long bytes = Files.size(path);
                Files.deleteIfExists(path);
                totalBytes -= bytes;
                segmentCount--;
            }
        }
    }

    private long pendingBytesFrom(WalCheckpoint frontier, List<Long> segments) throws IOException {
        long bytes = 0;
        for (long segmentId : segments) {
            if (segmentId < frontier.segmentId()) {
                continue;
            }
            long size = Files.size(segmentPath(segmentId));
            bytes += segmentId == frontier.segmentId() ? size - frontier.offset() : size;
        }
        return bytes;
    }

    private long bytesBetween(WalCheckpoint from, WalCheckpoint to, List<Long> segments) throws IOException {
        if (from.segmentId() == to.segmentId()) {
            return to.offset() - from.offset();
        }
        long bytes = Files.size(segmentPath(from.segmentId())) - from.offset();
        for (long segmentId : segments) {
            if (segmentId > from.segmentId() && segmentId < to.segmentId()) {
                bytes += Files.size(segmentPath(segmentId));
            }
        }
        return bytes + to.offset();
    }

    private void writeCheckpoint(WalCheckpoint value) throws IOException {
        ByteBuffer content = ByteBuffer.allocate(CHECKPOINT_BYTES);
        content.putLong(value.segmentId());
        content.putLong(value.offset());
        content.putLong(value.sequence());
        byte[] checksumInput = java.util.Arrays.copyOf(content.array(), Long.BYTES * 3);
        content.putInt(checksum(checksumInput));
        Files.write(directory.resolve(CHECKPOINT_TEMP_FILE), content.array(),
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
        try {
            Files.move(directory.resolve(CHECKPOINT_TEMP_FILE), directory.resolve(CHECKPOINT_FILE),
                    StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(directory.resolve(CHECKPOINT_TEMP_FILE), directory.resolve(CHECKPOINT_FILE),
                    StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private java.util.Optional<WalCheckpoint> readCheckpoint() throws IOException {
        Path path = directory.resolve(CHECKPOINT_FILE);
        if (!Files.exists(path)) {
            return java.util.Optional.empty();
        }
        byte[] content = Files.readAllBytes(path);
        if (content.length != CHECKPOINT_BYTES) {
            throw new IOException("Invalid WAL checkpoint length");
        }
        ByteBuffer buffer = ByteBuffer.wrap(content);
        long segmentId = buffer.getLong();
        long offset = buffer.getLong();
        long sequence = buffer.getLong();
        int expectedChecksum = buffer.getInt();
        if (checksum(java.util.Arrays.copyOf(content, Long.BYTES * 3)) != expectedChecksum) {
            throw new IOException("Invalid WAL checkpoint checksum");
        }
        return java.util.Optional.of(new WalCheckpoint(segmentId, offset, sequence));
    }

    private void validateCheckpoint(List<Long> segments) throws IOException {
        if (!segments.contains(checkpoint.segmentId())) {
            throw new IOException("Checkpoint references a missing WAL segment");
        }
        long size = Files.size(segmentPath(checkpoint.segmentId()));
        if (checkpoint.offset() < 0 || checkpoint.offset() > size || checkpoint.sequence() >= nextSequence) {
            throw new IOException("Checkpoint is outside WAL bounds");
        }
    }

    private long lastSequence(long segmentId) throws IOException {
        long last = -1;
        try (FileChannel channel = FileChannel.open(segmentPath(segmentId), StandardOpenOption.READ)) {
            long size = channel.size();
            long offset = 0;
            while (offset < size) {
                WalRecord record = readRecord(channel, segmentId, offset, size);
                if (record == null) {
                    break;
                }
                last = record.sequence();
                offset = record.endOffset();
            }
        }
        return last;
    }

    private List<Long> segmentIds() throws IOException {
        List<Long> ids = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(directory, "wal-*.log")) {
            for (Path path : stream) {
                String name = path.getFileName().toString();
                ids.add(Long.parseLong(name.substring(4, name.length() - 4)));
            }
        }
        ids.sort(Comparator.naturalOrder());
        return ids;
    }

    private Path segmentPath(long segmentId) {
        return directory.resolve("wal-%020d.log".formatted(segmentId));
    }

    private static void readFully(FileChannel channel, ByteBuffer target, long position) throws IOException {
        while (target.hasRemaining()) {
            int read = channel.read(target, position);
            if (read < 0) {
                throw new EOFException();
            }
            position += read;
        }
    }

    private static int checksum(long sequence, byte[] payload) {
        ByteBuffer input = ByteBuffer.allocate(Long.BYTES + payload.length);
        input.putLong(sequence).put(payload);
        return checksum(input.array());
    }

    private static int checksum(byte[] value) {
        CRC32C crc = new CRC32C();
        crc.update(value, 0, value.length);
        return (int) crc.getValue();
    }
}
