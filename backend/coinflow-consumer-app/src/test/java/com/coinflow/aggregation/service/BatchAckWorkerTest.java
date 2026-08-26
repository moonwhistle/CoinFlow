package com.coinflow.aggregation.service;

import com.coinflow.config.properties.TickConsumerProperties;
import com.coinflow.monitoring.MetricRecorder;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Timer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StreamOperations;

import static com.coinflow.monitoring.constant.MetricConstants.VALUE_FLUSH_INTERVAL;
import static com.coinflow.monitoring.constant.MetricConstants.VALUE_FLUSH_SIZE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BatchAckWorkerTest {

    @Mock
    private RedisTemplate<String, String> redisTemplate;

    @Mock
    private StreamOperations<String, Object, Object> streamOperations;

    @Mock
    private MetricRecorder metricRecorder;

    @Mock
    private Timer ackLatencyTimer;

    @Mock
    private Counter defaultCounter;

    @Mock
    private Counter sizeFlushCounter;

    @Mock
    private Counter intervalFlushCounter;

    private BatchAckWorker worker;

    @BeforeEach
    void setUp() {
        TickConsumerProperties properties = new TickConsumerProperties(
                "tick:raw", "tick-consumer-group", "consumer-1", 200_000L, 0.8);

        when(metricRecorder.getTimer(anyString(), any(String[].class))).thenReturn(ackLatencyTimer);
        when(metricRecorder.getCounter(anyString(), any(String[].class))).thenAnswer(invocation -> {
            List<Object> arguments = Arrays.asList(invocation.getArguments());
            if (arguments.contains(VALUE_FLUSH_SIZE)) {
                return sizeFlushCounter;
            }
            if (arguments.contains(VALUE_FLUSH_INTERVAL)) {
                return intervalFlushCounter;
            }
            return defaultCounter;
        });
        doAnswer(invocation -> {
            invocation.<Runnable>getArgument(0).run();
            return null;
        }).when(ackLatencyTimer).record(any(Runnable.class));
        when(redisTemplate.opsForStream()).thenReturn(streamOperations);

        worker = new BatchAckWorker(redisTemplate, properties, metricRecorder);
        worker.init();
    }

    @AfterEach
    void tearDown() {
        worker.destroy();
    }

    @Test
    void coalescesSizeFlushRequestsAndKeepsPartialBatchForIntervalFlush() throws Exception {
        CountDownLatch firstXackStarted = new CountDownLatch(1);
        CountDownLatch releaseFirstXack = new CountDownLatch(1);
        AtomicInteger xackCalls = new AtomicInteger();
        AtomicLong thirdXackNanos = new AtomicLong();
        List<Integer> batchSizes = Collections.synchronizedList(new ArrayList<>());

        doAnswer(invocation -> {
            batchSizes.add(invocation.getArguments().length - 2);
            int call = xackCalls.incrementAndGet();
            if (call == 1) {
                firstXackStarted.countDown();
                assertThat(releaseFirstXack.await(1, TimeUnit.SECONDS)).isTrue();
            } else if (call == 3) {
                thirdXackNanos.set(System.nanoTime());
            }
            return 0L;
        }).when(streamOperations).acknowledge(anyString(), anyString(), any(RecordId[].class));

        addRecords(0, 500);
        assertThat(firstXackStarted.await(1, TimeUnit.SECONDS)).isTrue();

        // New records cross the threshold while the first size flush is still running.
        addRecords(500, 600);
        Thread.sleep(75);
        long firstXackReleasedNanos = System.nanoTime();
        releaseFirstXack.countDown();

        verify(sizeFlushCounter, timeout(1_000).times(2)).increment();
        verify(intervalFlushCounter, timeout(1_000).atLeastOnce()).increment();

        assertThat(batchSizes).containsExactly(500, 500, 100);
        assertThat(thirdXackNanos.get() - firstXackReleasedNanos)
                .isGreaterThanOrEqualTo(TimeUnit.MILLISECONDS.toNanos(40));
    }

    private void addRecords(long start, int count) {
        for (long sequence = start; sequence < start + count; sequence++) {
            worker.addAck(RecordId.of("1-" + sequence));
        }
    }
}
