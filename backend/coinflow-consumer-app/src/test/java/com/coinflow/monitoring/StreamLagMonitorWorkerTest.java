package com.coinflow.monitoring;

import com.coinflow.config.properties.TickConsumerProperties;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.RedisTemplate;

import static com.coinflow.monitoring.constant.MetricConstants.STREAM_BACKLOG_COUNT;
import static com.coinflow.monitoring.constant.MetricConstants.STREAM_BACKLOG_RETENTION_RATIO;
import static com.coinflow.monitoring.constant.MetricConstants.STREAM_RETENTION_WARNING_COUNT;
import static com.coinflow.monitoring.constant.MetricConstants.TAG_MODULE;
import static com.coinflow.monitoring.constant.MetricConstants.VALUE_MODULE_CONSUMER;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class StreamLagMonitorWorkerTest {

    @Mock
    private RedisTemplate<String, String> redisTemplate;

    @Mock
    private MetricRecorder metricRecorder;

    private final AtomicReference<Double> backlogGauge = new AtomicReference<>(0.0);
    private final AtomicReference<Double> retentionRatioGauge = new AtomicReference<>(0.0);

    private StreamLagMonitorWorker worker;

    @BeforeEach
    void setUp() {
        TickConsumerProperties properties = new TickConsumerProperties(
                "tick:raw", "tick-consumer-group", "consumer-1", 200_000L, 0.8);
        when(metricRecorder.registerGauge(
                STREAM_BACKLOG_COUNT, 0.0, TAG_MODULE, VALUE_MODULE_CONSUMER))
                .thenReturn(backlogGauge);
        when(metricRecorder.registerGauge(
                STREAM_BACKLOG_RETENTION_RATIO, 0.0, TAG_MODULE, VALUE_MODULE_CONSUMER))
                .thenReturn(retentionRatioGauge);

        worker = new StreamLagMonitorWorker(redisTemplate, properties, metricRecorder);
        worker.init();
    }

    @Test
    void recordsBacklogRetentionRatio() {
        worker.updateBacklogMetrics(100_000);

        assertThat(backlogGauge.get()).isEqualTo(100_000.0);
        assertThat(retentionRatioGauge.get()).isEqualTo(0.5);
    }

    @Test
    void recordsWarningOnlyWhenThresholdIsCrossed() {
        worker.updateBacklogMetrics(160_000);
        worker.updateBacklogMetrics(180_000);

        verify(metricRecorder, times(1)).increment(
                STREAM_RETENTION_WARNING_COUNT,
                TAG_MODULE,
                VALUE_MODULE_CONSUMER);

        worker.updateBacklogMetrics(100_000);
        worker.updateBacklogMetrics(170_000);

        verify(metricRecorder, times(2)).increment(
                STREAM_RETENTION_WARNING_COUNT,
                TAG_MODULE,
                VALUE_MODULE_CONSUMER);
    }
}
