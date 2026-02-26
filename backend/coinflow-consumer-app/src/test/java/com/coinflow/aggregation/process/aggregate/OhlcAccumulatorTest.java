package com.coinflow.aggregation.process.aggregate;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

class OhlcAccumulatorTest {

    @Test
    @DisplayName("Concurrency: accumulate volume correctly in multi-threaded environment")
    void accumulateVolume_Concurrency_Test() throws InterruptedException {
        // Given
        BigDecimal initialPrice = new BigDecimal("100");
        long initialVolume = 0L;
        Instant startTime = Instant.now();
        OhlcAccumulator accumulator = OhlcAccumulator.first(initialPrice, initialVolume, startTime, "0-0");

        int threadCount = 10;
        int additionsPerThread = 1000;
        long volumePerAddition = 1L;

        ExecutorService executorService = Executors.newFixedThreadPool(threadCount);
        CountDownLatch latch = new CountDownLatch(threadCount);
        AtomicInteger exceptionCount = new AtomicInteger();

        // When
        for (int i = 0; i < threadCount; i++) {
            executorService.submit(() -> {
                try {
                    for (int j = 0; j < additionsPerThread; j++) {
                        accumulator.apply(
                                new BigDecimal("100"),
                                volumePerAddition,
                                startTime.plusMillis(1),
                                "0-" + j);
                    }
                } catch (Exception e) {
                    exceptionCount.incrementAndGet();
                } finally {
                    latch.countDown();
                }
            });
        }

        latch.await(10, TimeUnit.SECONDS);
        executorService.shutdown();

        // Then
        long expectedVolume = threadCount * additionsPerThread * volumePerAddition;
        assertEquals(expectedVolume, accumulator.getVolume(),
                "Volume should match expected value. If this fails, it demonstrates a race condition.");
    }
}
