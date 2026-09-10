package com.coinflow.publish.stream;

import com.coinflow.monitoring.MetricRecorder;
import com.coinflow.publish.DeliveryStatusProvider;
import com.coinflow.publish.config.CollectorDeliveryProperties;
import com.coinflow.tick.publisher.TickPublisher;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.InitializingBean;

@Slf4j
public final class QueuedPipelinedTickPublisher
        implements TickPublisher, InitializingBean, DisposableBean, DeliveryStatusProvider {

    private static final String CONFIRMED_COUNT = "collector.delivery.confirmed.count";
    private static final String RETRY_COUNT = "collector.delivery.retry.count";
    private static final String QUEUE_SIZE = "collector.delivery.queue.size";
    private static final String PIPELINE_BATCH_SIZE = "collector.delivery.pipeline.batch.size";
    private static final String RETRY_FAILURE_MILLIS = "collector.delivery.consecutive.failure.millis";

    private final RedisStreamTickPublisher redisPublisher;
    private final MetricRecorder metrics;
    private final CollectorDeliveryProperties properties;
    private final ArrayBlockingQueue<byte[]> queue;
    private final AtomicBoolean running = new AtomicBoolean();
    private final AtomicLong accepted = new AtomicLong();
    private final AtomicLong confirmed = new AtomicLong();
    private final AtomicLong retries = new AtomicLong();
    private final AtomicLong retryStartedNanos = new AtomicLong();
    private volatile Thread worker;
    private volatile Throwable terminalFailure;

    public QueuedPipelinedTickPublisher(
            RedisStreamTickPublisher redisPublisher,
            MetricRecorder metrics,
            CollectorDeliveryProperties properties
    ) {
        this.redisPublisher = redisPublisher;
        this.metrics = metrics;
        this.properties = properties;
        this.queue = new ArrayBlockingQueue<>(properties.getQueueCapacity());
    }

    @Override
    public void afterPropertiesSet() {
        running.set(true);
        worker = new Thread(this::runWorker, "tick-pipeline-publisher");
        worker.setDaemon(true);
        worker.start();
    }

    @Override
    public void publish(byte[] rawData) {
        if (!running.get()) {
            throw new IllegalStateException("Pipeline publisher is not running", terminalFailure);
        }
        try {
            queue.put(rawData);
            accepted.incrementAndGet();
            metrics.recordValue(QUEUE_SIZE, queue.size());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while applying pipeline backpressure", e);
        }
    }

    private void runWorker() {
        try {
            while (running.get()) {
                List<byte[]> batch = takeBatch();
                if (!batch.isEmpty()) {
                    publishWithRetry(batch);
                    metrics.recordValue(PIPELINE_BATCH_SIZE, batch.size());
                    metrics.recordValue(QUEUE_SIZE, queue.size());
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Throwable t) {
            terminalFailure = t;
            running.set(false);
            log.error("Pipeline publisher worker stopped", t);
        }
    }

    private List<byte[]> takeBatch() throws InterruptedException {
        byte[] first = queue.take();
        List<byte[]> batch = new ArrayList<>(properties.getBatchSize());
        batch.add(first);
        long deadline = System.nanoTime() + properties.getFlushInterval().toNanos();
        while (batch.size() < properties.getBatchSize()) {
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) {
                break;
            }
            byte[] next = queue.poll(remaining, TimeUnit.NANOSECONDS);
            if (next == null) {
                break;
            }
            batch.add(next);
        }
        queue.drainTo(batch, properties.getBatchSize() - batch.size());
        return batch;
    }

    private void publishWithRetry(List<byte[]> batch) throws InterruptedException {
        long delayMillis = properties.getRetryInitialDelay().toMillis();
        long maxDelayMillis = properties.getRetryMaxDelay().toMillis();
        while (running.get()) {
            try {
                redisPublisher.publishBatch(batch);
                retryStartedNanos.set(0);
                metrics.recordValue(RETRY_FAILURE_MILLIS, 0);
                metrics.increment(CONFIRMED_COUNT, batch.size());
                confirmed.addAndGet(batch.size());
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

    @Override
    public void destroy() throws InterruptedException {
        running.set(false);
        Thread currentWorker = worker;
        if (currentWorker != null) {
            currentWorker.interrupt();
            currentWorker.join(Duration.ofSeconds(5).toMillis());
        }
    }

    @Override
    public boolean isHealthy() {
        Thread currentWorker = worker;
        return running.get() && currentWorker != null && currentWorker.isAlive();
    }

    @Override
    public Map<String, Object> details() {
        return Map.of(
                "mode", "PIPELINE",
                "accepted", accepted.get(),
                "confirmed", confirmed.get(),
                "retryCount", retries.get(),
                "consecutiveFailureMillis", consecutiveFailureMillis(),
                "queueSize", queue.size(),
                "queueCapacity", queue.remainingCapacity() + queue.size());
    }

    private long consecutiveFailureMillis() {
        long started = retryStartedNanos.get();
        return started == 0 ? 0 : TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
    }
}
