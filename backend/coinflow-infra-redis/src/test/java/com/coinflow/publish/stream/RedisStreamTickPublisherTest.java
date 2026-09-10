package com.coinflow.publish.stream;

import com.coinflow.monitoring.MetricRecorder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.Callable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.data.redis.serializer.RedisSerializer;

import static com.coinflow.monitoring.constant.MetricConstants.STREAM_PUBLISH_LATENCY;
import static com.coinflow.monitoring.constant.MetricConstants.STREAM_PUBLISH_FAILURE_COUNT;
import static com.coinflow.monitoring.constant.MetricConstants.TAG_MODULE;
import static com.coinflow.monitoring.constant.MetricConstants.VALUE_MODULE_COLLECTOR;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RedisStreamTickPublisherTest {

    @Mock
    private RedisTemplate<String, byte[]> redisTemplate;

    @Mock
    private MetricRecorder metricRecorder;

    private RedisStreamTickPublisher publisher;

    @BeforeEach
    void setUp() throws Exception {
        publisher = new RedisStreamTickPublisher(
                redisTemplate, metricRecorder, "tick:raw", "ticker:broadcast", 200_000L);

        when(metricRecorder.recordTime(
                eq(STREAM_PUBLISH_LATENCY), any(Callable.class), any(String[].class)))
                .thenAnswer(invocation -> {
                    Callable<?> callable = invocation.getArgument(1);
                    return callable.call();
                });
    }

    @Test
    void storesInStreamAndBroadcastsTickerWithOneLuaExecution() {
        byte[] payload = new byte[]{1, 2, 3};
        String tickerPayload = "{\"symbol\":\"btcusdt\",\"price\":100}";
        when(redisTemplate.<String>execute(
                any(RedisScript.class),
                any(RedisSerializer.class),
                any(RedisSerializer.class),
                anyList(),
                any(Object[].class)
        )).thenReturn("1-0");

        publisher.publish(payload, tickerPayload);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<RedisScript<String>> scriptCaptor = ArgumentCaptor.forClass(RedisScript.class);
        ArgumentCaptor<Object[]> argsCaptor = ArgumentCaptor.forClass(Object[].class);
        verify(redisTemplate).execute(
                scriptCaptor.capture(),
                any(RedisSerializer.class),
                any(RedisSerializer.class),
                eq(List.of("tick:raw", "ticker:broadcast")),
                argsCaptor.capture()
        );

        assertThat(scriptCaptor.getValue().getScriptAsString()).contains("XADD", "MAXLEN", "PUBLISH");
        assertThat(argsCaptor.getValue()).containsExactly(
                "200000".getBytes(StandardCharsets.UTF_8),
                payload,
                tickerPayload.getBytes(StandardCharsets.UTF_8)
        );
    }

    @Test
    void recordsPublishFailureWhenRedisRejectsXadd() {
        when(redisTemplate.<String>execute(
                any(RedisScript.class),
                any(RedisSerializer.class),
                any(RedisSerializer.class),
                anyList(),
                any(Object[].class)
        ))
                .thenThrow(new RuntimeException("OOM command not allowed"));

        assertThatThrownBy(() -> publisher.publish(new byte[]{1, 2, 3}, "{}"))
                .isInstanceOf(RuntimeException.class);

        verify(metricRecorder).increment(
                STREAM_PUBLISH_FAILURE_COUNT,
                TAG_MODULE,
                VALUE_MODULE_COLLECTOR);
    }
}
