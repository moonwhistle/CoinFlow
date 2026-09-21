package com.coinflow.publish.wal;

import com.coinflow.monitoring.MetricRecorder;
import com.coinflow.publish.DeliveryStatusProvider;
import com.coinflow.publish.config.CollectorDeliveryProperties;
import com.coinflow.publish.stream.RedisStreamTickPublisher;
import com.coinflow.tick.publisher.TickPublisher;
import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.InitializingBean;

@Slf4j
public final class WalPipelinedTickPublisher
        implements TickPublisher, InitializingBean, DisposableBean, DeliveryStatusProvider {

    private static final String WAL_APPEND_LATENCY = "collector.wal.append.latency";
    private static final String WAL_APPEND_COUNT = "collector.wal.append.count";
    private static final String WAL_BYTES = "collector.wal.bytes";
    private static final String WAL_APPEND_BYTES_COUNT = "collector.wal.append.bytes";
    private static final String WAL_PENDING = "collector.wal.pending.records";
    private static final String WAL_PENDING_BYTES = "collector.wal.pending.bytes";
    private static final String WAL_SEGMENTS = "collector.wal.segments";
    private static final String WAL_CAPACITY_RATIO = "collector.wal.capacity.ratio";
    private static final String CONFIRMED_COUNT = "collector.delivery.confirmed.count";
    private static final String RETRY_COUNT = "collector.delivery.retry.count";
    private static final String RETRY_FAILURE_MILLIS = "collector.delivery.consecutive.failure.millis";
    private static final String PIPELINE_BATCH_SIZE = "collector.delivery.pipeline.batch.size";
    private static final String CHECKPOINT_SEQUENCE = "collector.wal.checkpoint.sequence";

    private final RedisStreamTickPublisher redisPublisher;
    private final MetricRecorder metrics;
    private final CollectorDeliveryProperties properties;
    private final LocalTickWal wal;
    private final Semaphore appendedSignal = new Semaphore(0);
    private final AtomicBoolean running = new AtomicBoolean();
    private final AtomicLong accepted = new AtomicLong();
    private final AtomicLong confirmed = new AtomicLong();
    private final AtomicLong retries = new AtomicLong();
    private final AtomicLong retryStartedNanos = new AtomicLong();
    private volatile Thread worker;
    private volatile Throwable terminalFailure;

    public WalPipelinedTickPublisher(
            RedisStreamTickPublisher redisPublisher,
            MetricRecorder metrics,
            CollectorDeliveryProperties properties
    ) throws IOException {
        this.redisPublisher = redisPublisher;
        this.metrics = metrics;
        this.properties = properties;
        this.wal = new LocalTickWal(properties.getWalDirectory(),
                properties.getWalSegmentBytes(), properties.getWalMaxBytes());
    }

    @Override
    public void afterPropertiesSet() {
        running.set(true);
        worker = new Thread(this::runWorker, "tick-wal-publisher");
        worker.setDaemon(true);
        worker.start();
        appendedSignal.release();
    }

    @Override
    public void publish(byte[] rawData) {
        if (!running.get()) {
            throw new IllegalStateException("WAL publisher is not running", terminalFailure);
        }
        long startNanos = System.nanoTime();
        try {
            WalRecord appended = wal.append(rawData);
            accepted.incrementAndGet();
            metrics.recordTimeNanos(WAL_APPEND_LATENCY, System.nanoTime() - startNanos);
            metrics.increment(WAL_APPEND_COUNT);
            metrics.increment(WAL_APPEND_BYTES_COUNT, appended.endOffset() - appended.startOffset());
            recordWalState();
            appendedSignal.release();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while applying WAL capacity backpressure", e);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to append tick to local WAL", e);
        }
    }

    private void runWorker() {
        try {
            while (running.get()) {
                List<WalRecord> batch = wal.readPending(properties.getBatchSize());
                if (batch.isEmpty()) {
                    appendedSignal.tryAcquire(100, TimeUnit.MILLISECONDS);
                    continue;
                }
                if (batch.size() < properties.getBatchSize()) {
                    appendedSignal.drainPermits();
                    TimeUnit.NANOSECONDS.sleep(properties.getFlushInterval().toNanos());
                    batch = wal.readPending(properties.getBatchSize());
                }
                publishWithRetry(batch);
                if (!running.get()) {
                    break;
                }
                WalRecord last = batch.get(batch.size() - 1);
                wal.commit(last);
                metrics.recordValue(PIPELINE_BATCH_SIZE, batch.size());
                metrics.increment(CONFIRMED_COUNT, batch.size());
                confirmed.addAndGet(batch.size());
                metrics.recordValue(CHECKPOINT_SEQUENCE, last.sequence());
                recordWalState();
                appendedSignal.drainPermits();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Throwable t) {
            terminalFailure = t;
            running.set(false);
            log.error("WAL publisher worker stopped", t);
        }
    }

    private void publishWithRetry(List<WalRecord> batch) throws InterruptedException {
        List<byte[]> payloads = batch.stream().map(WalRecord::payload).toList();
        long delayMillis = properties.getRetryInitialDelay().toMillis();
        long maxDelayMillis = properties.getRetryMaxDelay().toMillis();
        while (running.get()) {
            try {
                redisPublisher.publishBatch(payloads);
                retryStartedNanos.set(0);
                metrics.recordValue(RETRY_FAILURE_MILLIS, 0);
                return;
            } catch (RuntimeException e) {
                metrics.increment(RETRY_COUNT);
                retries.incrementAndGet();
                long failureStarted = retryStartedNanos.updateAndGet(
                        existing -> existing == 0 ? System.nanoTime() : existing);
                metrics.recordValue(RETRY_FAILURE_MILLIS,
                        TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - failureStarted));
                long jitter = java.util.concurrent.ThreadLocalRandom.current()
                        .nextLong(Math.max(1, delayMillis / 5 + 1));
                Thread.sleep(Math.min(maxDelayMillis, delayMillis + jitter));
                delayMillis = Math.min(maxDelayMillis, delayMillis * 2);
            }
        }
    }

    private void recordWalState() {
        metrics.recordValue(WAL_BYTES, wal.totalBytes());
        metrics.recordValue(WAL_PENDING, wal.pendingRecords());
        metrics.recordValue(WAL_PENDING_BYTES, wal.pendingBytes());
        metrics.recordValue(WAL_SEGMENTS, wal.segmentCount());
        metrics.recordValue(WAL_CAPACITY_RATIO, (double) wal.totalBytes() / properties.getWalMaxBytes());
    }

    @Override
    public void destroy() throws Exception {
        running.set(false);
        wal.stopAccepting();
        appendedSignal.release();
        Thread currentWorker = worker;
        if (currentWorker != null) {
            currentWorker.interrupt();
            currentWorker.join(Duration.ofSeconds(5).toMillis());
        }
        wal.close();
    }

    @Override
    public boolean isHealthy() {
        Thread currentWorker = worker;
        return running.get() && currentWorker != null && currentWorker.isAlive() && !wal.isAtCapacity();
    }

    @Override
    public Map<String, Object> details() {
        return Map.ofEntries(
                Map.entry("mode", "WAL_PIPELINE"),
                Map.entry("accepted", accepted.get()),
                Map.entry("confirmed", confirmed.get()),
                Map.entry("walBytes", wal.totalBytes()),
                Map.entry("walPendingBytes", wal.pendingBytes()),
                Map.entry("walAppendBytes", wal.appendBytes()),
                Map.entry("walPendingRecords", wal.pendingRecords()),
                Map.entry("walSegments", wal.segmentCount()),
                Map.entry("checkpointSequence", wal.checkpoint().sequence()),
                Map.entry("retryCount", retries.get()),
                Map.entry("consecutiveFailureMillis", consecutiveFailureMillis()),
                Map.entry("walCapacityRatio", (double) wal.totalBytes() / properties.getWalMaxBytes()),
                Map.entry("workerAlive", worker != null && worker.isAlive()));
    }

    private long consecutiveFailureMillis() {
        long started = retryStartedNanos.get();
        return started == 0 ? 0 : TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
    }
}
