package com.coinflow.publish.pubsub;

import static com.coinflow.monitoring.constant.MetricConstants.TAG_MODULE;
import static com.coinflow.monitoring.constant.MetricConstants.TICKER_PUBLISH_FAILURE_COUNT;
import static com.coinflow.monitoring.constant.MetricConstants.TICKER_PUBLISH_LATENCY;
import static com.coinflow.monitoring.constant.MetricConstants.VALUE_MODULE_COLLECTOR;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.coinflow.monitoring.MetricRecorder;
import com.coinflow.publish.exception.PublishException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;

@ExtendWith(MockitoExtension.class)
class RedisTickerPublisherTest {

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private MetricRecorder metricRecorder;

    private RedisTickerPublisher publisher;

    @BeforeEach
    void setUp() {
        publisher = new RedisTickerPublisher(
                redisTemplate, metricRecorder, "ticker:broadcast");
    }

    @Test
    void publishesTickerToConfiguredTopic() {
        String payload = "{\"symbol\":\"btcusdt\",\"price\":100}";
        when(redisTemplate.convertAndSend(anyString(), anyString())).thenReturn(1L);

        publisher.publish(payload);

        verify(redisTemplate).convertAndSend("ticker:broadcast", payload);
        verify(metricRecorder).recordTimeNanos(
                org.mockito.ArgumentMatchers.eq(TICKER_PUBLISH_LATENCY),
                org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.eq(TAG_MODULE),
                org.mockito.ArgumentMatchers.eq(VALUE_MODULE_COLLECTOR)
        );
    }

    @Test
    void recordsFailureAndPropagatesException() {
        when(redisTemplate.convertAndSend(anyString(), anyString()))
                .thenThrow(new RuntimeException("Redis unavailable"));

        assertThatThrownBy(() -> publisher.publish("{}"))
                .isInstanceOf(PublishException.class);
        verify(metricRecorder).increment(
                TICKER_PUBLISH_FAILURE_COUNT,
                TAG_MODULE,
                VALUE_MODULE_COLLECTOR
        );
    }
}
