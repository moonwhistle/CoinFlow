package com.coinflow.publish.config;

import java.nio.file.Path;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "collector.delivery")
public class CollectorDeliveryProperties {

    public enum Mode { DIRECT, PIPELINE, WAL_PIPELINE }

    private Mode mode = Mode.DIRECT;
    private int batchSize = 500;
    private Duration flushInterval = Duration.ofMillis(10);
    private int queueCapacity = 100_000;
    private Duration retryInitialDelay = Duration.ofMillis(100);
    private Duration retryMaxDelay = Duration.ofSeconds(5);
    private Path walDirectory = Path.of("./data/collector-wal");
    private long walSegmentBytes = 128L * 1024 * 1024;
    private long walMaxBytes = 1024L * 1024 * 1024;

    public Mode getMode() { return mode; }
    public void setMode(Mode mode) { this.mode = mode; }
    public int getBatchSize() { return batchSize; }
    public void setBatchSize(int batchSize) { this.batchSize = batchSize; }
    public Duration getFlushInterval() { return flushInterval; }
    public void setFlushInterval(Duration flushInterval) { this.flushInterval = flushInterval; }
    public int getQueueCapacity() { return queueCapacity; }
    public void setQueueCapacity(int queueCapacity) { this.queueCapacity = queueCapacity; }
    public Duration getRetryInitialDelay() { return retryInitialDelay; }
    public void setRetryInitialDelay(Duration retryInitialDelay) { this.retryInitialDelay = retryInitialDelay; }
    public Duration getRetryMaxDelay() { return retryMaxDelay; }
    public void setRetryMaxDelay(Duration retryMaxDelay) { this.retryMaxDelay = retryMaxDelay; }
    public Path getWalDirectory() { return walDirectory; }
    public void setWalDirectory(Path walDirectory) { this.walDirectory = walDirectory; }
    public long getWalSegmentBytes() { return walSegmentBytes; }
    public void setWalSegmentBytes(long walSegmentBytes) { this.walSegmentBytes = walSegmentBytes; }
    public long getWalMaxBytes() { return walMaxBytes; }
    public void setWalMaxBytes(long walMaxBytes) { this.walMaxBytes = walMaxBytes; }

    public void validate() {
        if (mode == null || batchSize <= 0 || queueCapacity <= 0
                || flushInterval == null || flushInterval.isNegative() || flushInterval.isZero()
                || retryInitialDelay == null || retryInitialDelay.isNegative() || retryInitialDelay.isZero()
                || retryMaxDelay == null || retryMaxDelay.compareTo(retryInitialDelay) < 0
                || walDirectory == null || walSegmentBytes <= 0 || walMaxBytes < walSegmentBytes) {
            throw new IllegalArgumentException("Invalid collector.delivery configuration");
        }
    }
}
