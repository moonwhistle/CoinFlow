package com.coinflow.publish.stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

import com.coinflow.monitoring.MetricRecorder;
import com.coinflow.publish.config.CollectorDeliveryProperties;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;

class QueuedPipelinedTickPublisherTest {

    @Test
    void flushesImmediatelyWhenBatchSizeIsReached() throws Exception {
        List<Integer> batchSizes = new CopyOnWriteArrayList<>();
        QueuedPipelinedTickPublisher publisher = publisher(3, Duration.ofSeconds(1), batchSizes);
        publisher.afterPropertiesSet();
        try {
            publisher.publish(new byte[] {1});
            publisher.publish(new byte[] {2});
            publisher.publish(new byte[] {3});

            await().atMost(Duration.ofSeconds(1)).untilAsserted(() ->
                    assertThat(batchSizes).containsExactly(3));
        } finally {
            publisher.destroy();
        }
    }

    @Test
    void flushesAPartialBatchAfterTheInterval() throws Exception {
        List<Integer> batchSizes = new CopyOnWriteArrayList<>();
        QueuedPipelinedTickPublisher publisher = publisher(10, Duration.ofMillis(20), batchSizes);
        publisher.afterPropertiesSet();
        try {
            publisher.publish(new byte[] {1});

            await().atMost(Duration.ofSeconds(1)).untilAsserted(() ->
                    assertThat(batchSizes).containsExactly(1));
        } finally {
            publisher.destroy();
        }
    }

    private QueuedPipelinedTickPublisher publisher(
            int batchSize,
            Duration flushInterval,
            List<Integer> observedBatchSizes
    ) {
        RedisStreamTickPublisher redisPublisher = mock(RedisStreamTickPublisher.class);
        doAnswer(invocation -> {
            observedBatchSizes.add(invocation.<List<byte[]>>getArgument(0).size());
            return null;
        }).when(redisPublisher).publishBatch(anyList());

        CollectorDeliveryProperties properties = new CollectorDeliveryProperties();
        properties.setBatchSize(batchSize);
        properties.setFlushInterval(flushInterval);
        return new QueuedPipelinedTickPublisher(redisPublisher, mock(MetricRecorder.class), properties);
    }
}
