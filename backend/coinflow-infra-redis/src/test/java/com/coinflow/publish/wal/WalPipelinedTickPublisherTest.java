package com.coinflow.publish.wal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.coinflow.monitoring.MetricRecorder;
import com.coinflow.publish.config.CollectorDeliveryProperties;
import com.coinflow.publish.stream.RedisStreamTickPublisher;
import java.nio.file.Path;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WalPipelinedTickPublisherTest {

    @TempDir
    Path tempDir;

    @Test
    void retriesTheSameBatchBeforeAdvancingCheckpoint() throws Exception {
        RedisStreamTickPublisher redisPublisher = mock(RedisStreamTickPublisher.class);
        MetricRecorder metrics = mock(MetricRecorder.class);
        CollectorDeliveryProperties properties = properties();
        doThrow(new RuntimeException("ambiguous response"))
                .doNothing()
                .when(redisPublisher).publishBatch(anyList());

        WalPipelinedTickPublisher publisher = new WalPipelinedTickPublisher(redisPublisher, metrics, properties);
        publisher.afterPropertiesSet();
        try {
            publisher.publish(new byte[] {1});
            publisher.publish(new byte[] {2});

            await().atMost(Duration.ofSeconds(3))
                    .untilAsserted(() -> assertThat((long) publisher.details().get("checkpointSequence"))
                            .isEqualTo(1L));
            verify(redisPublisher, atLeast(2)).publishBatch(anyList());
            assertThat(publisher.details().get("walPendingRecords")).isEqualTo(0L);
        } finally {
            publisher.destroy();
        }
    }

    private CollectorDeliveryProperties properties() {
        CollectorDeliveryProperties properties = new CollectorDeliveryProperties();
        properties.setWalDirectory(tempDir);
        properties.setWalSegmentBytes(1024);
        properties.setWalMaxBytes(4096);
        properties.setBatchSize(10);
        properties.setFlushInterval(Duration.ofMillis(5));
        properties.setRetryInitialDelay(Duration.ofMillis(1));
        properties.setRetryMaxDelay(Duration.ofMillis(5));
        return properties;
    }
}
