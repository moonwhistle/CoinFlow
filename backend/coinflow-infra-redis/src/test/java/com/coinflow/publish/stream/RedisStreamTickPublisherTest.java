package com.coinflow.publish.stream;

import com.coinflow.monitoring.MetricRecorder;
import java.util.concurrent.Callable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.connection.RedisStreamCommands.XAddOptions;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StreamOperations;

import static com.coinflow.monitoring.constant.MetricConstants.STREAM_PUBLISH_LATENCY;
import static com.coinflow.publish.stream.RedisStreamTickPublisher.RAW_PAYLOAD_FIELD;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RedisStreamTickPublisherTest {

    @Mock
    private RedisTemplate<String, byte[]> redisTemplate;

    @Mock
    private StreamOperations<String, String, byte[]> streamOperations;

    @Mock
    private MetricRecorder metricRecorder;

    private RedisStreamTickPublisher publisher;

    @BeforeEach
    void setUp() throws Exception {
        publisher = new RedisStreamTickPublisher(
                redisTemplate, metricRecorder, "tick:raw", 200_000L);

        when(redisTemplate.<String, byte[]>opsForStream()).thenReturn(streamOperations);
        when(streamOperations.add(any(MapRecord.class), any(XAddOptions.class)))
                .thenReturn(RecordId.of("1-0"));
        when(metricRecorder.recordTime(
                eq(STREAM_PUBLISH_LATENCY), any(Callable.class), any(String[].class)))
                .thenAnswer(invocation -> {
                    Callable<?> callable = invocation.getArgument(1);
                    return callable.call();
                });
    }

    @Test
    void publishesWithConfiguredApproximateMaxLength() {
        byte[] payload = new byte[]{1, 2, 3};

        publisher.publish(payload);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<MapRecord<String, String, byte[]>> recordCaptor =
                ArgumentCaptor.forClass(MapRecord.class);
        ArgumentCaptor<XAddOptions> optionsCaptor = ArgumentCaptor.forClass(XAddOptions.class);
        verify(streamOperations).add(recordCaptor.capture(), optionsCaptor.capture());

        assertThat(recordCaptor.getValue().getStream()).isEqualTo("tick:raw");
        assertThat(recordCaptor.getValue().getValue().get(RAW_PAYLOAD_FIELD)).isEqualTo(payload);
        assertThat(optionsCaptor.getValue().getMaxlen()).isEqualTo(200_000L);
        assertThat(optionsCaptor.getValue().isApproximateTrimming()).isTrue();
    }
}
