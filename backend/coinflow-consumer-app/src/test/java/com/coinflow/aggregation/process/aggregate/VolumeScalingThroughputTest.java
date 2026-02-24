package com.coinflow.aggregation.process.aggregate;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class VolumeScalingThroughputTest {

    // 블로그 작성용 타임 윈도우(초)
    private static final int TEST_DURATION_SECONDS = 3;
    private static final int THREAD_COUNT = 100;

    @Test
    @DisplayName("동시성 환경에서의 TPS 한계 테스트: BigDecimal vs Long")
    void performance_Throughput_Concurrency_Test() throws InterruptedException {
        System.out.println("\n🚀 멀티 쓰레드(" + THREAD_COUNT + "개) 동시 누적 TPS 극한 측정 🚀");

        long bigDecimalTps = runBigDecimalThroughputTest();
        long longTps = runLongThroughputTest();

        System.out.println("\n📊 최종 결과 요약 📊");
        System.out.println("BigDecimal TPS: " + String.format("%,d", bigDecimalTps) + " ops/sec");
        System.out.println("Long Scaling TPS: " + String.format("%,d", longTps) + " ops/sec");

        // Long 방식이 객체 할당/GC 부하가 없어 압도적으로 처리량이 높아야 함
        assertThat(longTps).isGreaterThan(bigDecimalTps);
    }

    private long runBigDecimalThroughputTest() throws InterruptedException {
        ExecutorService executor = Executors.newFixedThreadPool(THREAD_COUNT);
        final AtomicReference<BigDecimal> accumulator = new AtomicReference<>(BigDecimal.ZERO);
        final BigDecimal tickVolume = new BigDecimal("0.0001");
        final AtomicInteger processCount = new AtomicInteger(0);

        System.out.println("\n▶ [1] BigDecimal 기반 처리 시작...");
        long startTime = System.currentTimeMillis();
        long endTime = startTime + (TEST_DURATION_SECONDS * 1000L);

        CountDownLatch startLatch = new CountDownLatch(1);

        for (int i = 0; i < THREAD_COUNT; i++) {
            executor.submit(() -> {
                try {
                    startLatch.await(); // 모든 쓰레드가 동시에 출발하도록 대기
                    while (System.currentTimeMillis() < endTime) {
                        // 멀티스레드 동시 접근 상황 시뮬레이션 (CAS Lock)
                        accumulator.updateAndGet(current -> current.add(tickVolume));
                        processCount.incrementAndGet();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
        }

        startLatch.countDown(); // 레이스 시작!
        executor.shutdown();
        while (!executor.isTerminated()) {
            Thread.sleep(100);
        }

        long totalOps = processCount.get();
        long tps = totalOps / TEST_DURATION_SECONDS;
        System.out.println("총 연산 횟수: " + String.format("%,d", totalOps) + " 번");
        return tps;
    }

    private long runLongThroughputTest() throws InterruptedException {
        ExecutorService executor = Executors.newFixedThreadPool(THREAD_COUNT);
        final AtomicLong accumulator = new AtomicLong(0L);
        final long tickVolume = 10000L; // 0.0001의 스케일드(10^8) 값
        final AtomicInteger processCount = new AtomicInteger(0);

        System.out.println("\n▶ [2] Long Scaling(초고속 Primitive) 처리 시작...");
        long startTime = System.currentTimeMillis();
        long endTime = startTime + (TEST_DURATION_SECONDS * 1000L);

        CountDownLatch startLatch = new CountDownLatch(1);

        for (int i = 0; i < THREAD_COUNT; i++) {
            executor.submit(() -> {
                try {
                    startLatch.await();
                    while (System.currentTimeMillis() < endTime) {
                        // 멀티스레드 동시 접근 상황 시뮬레이션 (CAS Lock) - Math.addExact와 동일 연산
                        accumulator.accumulateAndGet(tickVolume, Math::addExact);
                        processCount.incrementAndGet();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
        }

        startLatch.countDown(); // 레이스 시작!
        executor.shutdown();
        while (!executor.isTerminated()) {
            Thread.sleep(100);
        }

        long totalOps = processCount.get();
        long tps = totalOps / TEST_DURATION_SECONDS;
        System.out.println("총 연산 횟수: " + String.format("%,d", totalOps) + " 번");
        return tps;
    }
}
