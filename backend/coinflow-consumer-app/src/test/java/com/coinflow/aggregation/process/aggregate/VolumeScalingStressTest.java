package com.coinflow.aggregation.process.aggregate;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

class VolumeScalingStressTest {

    private static final int THREAD_COUNT = 100;
    private static final int SYMBOL_COUNT = 100;
    // GC 영향을 충분히 보기 위해 10초 이상의 긴 런타임 부여
    private static final int TEST_DURATION_SECONDS = 10;

    @Test
    @DisplayName("다중 종목(100개) 실거래 상황 인메모리 누적 스트레스 테스트: BigDecimal vs Long")
    void stress_MultiSymbol_BigDecimal_Vs_Long_Test() throws InterruptedException {
        System.out.println("\n🚀 실제 상황(100개 종목) 동시 누적 극한의 스트레스 테스트 🚀");

        long bigDecimalTotalOps = runBigDecimalMultiSymbolStressTest();

        // 메모리 청소를 위해 충분한 휴식 타임 (다음 테스트 영향 방지)
        System.gc();
        Thread.sleep(2000);

        long longTotalOps = runLongMultiSymbolStressTest();

        System.out.println("\n📊 " + TEST_DURATION_SECONDS + "초간 최종 누적 연산 처리량 비교 📊");
        System.out.println("BigDecimal 총 처리량: " + String.format("%,d", bigDecimalTotalOps) + " 번");
        System.out.println("Long Scaling 총 처리량: " + String.format("%,d", longTotalOps) + " 번");

        System.out.println(
                "\n💡 분석 결론: 코인의 개수가 늘어남에 따라(락 경합 분산), Long Scaling의 압도적인 순수 연산 속도와 무(無)객체 생성에 따른 GC 오버헤드 면제 덕분에 TPS 차이가 극명하게 발생합니다.");
    }

    private long runBigDecimalMultiSymbolStressTest() throws InterruptedException {
        ExecutorService executor = Executors.newFixedThreadPool(THREAD_COUNT);
        Map<Integer, AtomicReference<BigDecimal>> store = new ConcurrentHashMap<>();
        for (int i = 0; i < SYMBOL_COUNT; i++) {
            store.put(i, new AtomicReference<>(BigDecimal.ZERO));
        }

        final BigDecimal tickVolume = new BigDecimal("0.0001");
        AtomicLong totalOps = new AtomicLong(0);

        System.out.println("\n▶ [1] BigDecimal 기반 다중 종목 쓰레드 폭풍 유입 시작...");
        long startTime = System.currentTimeMillis();
        long endTime = startTime + (TEST_DURATION_SECONDS * 1000L);

        CountDownLatch startLatch = new CountDownLatch(1);

        for (int i = 0; i < THREAD_COUNT; i++) {
            executor.submit(() -> {
                try {
                    startLatch.await();
                    long localOps = 0;
                    while (System.currentTimeMillis() < endTime) {
                        // 0~99 난수 종목에 접근 (락 경합 분산)
                        int targetSymbolIndex = (int) (Math.random() * SYMBOL_COUNT);
                        store.get(targetSymbolIndex).updateAndGet(current -> current.add(tickVolume));
                        localOps++;
                    }
                    totalOps.addAndGet(localOps);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
        }

        startLatch.countDown();
        executor.shutdown();
        while (!executor.isTerminated()) {
            Thread.sleep(100);
        }

        return totalOps.get();
    }

    private long runLongMultiSymbolStressTest() throws InterruptedException {
        ExecutorService executor = Executors.newFixedThreadPool(THREAD_COUNT);
        Map<Integer, AtomicLong> store = new ConcurrentHashMap<>();
        for (int i = 0; i < SYMBOL_COUNT; i++) {
            store.put(i, new AtomicLong(0L));
        }

        final long tickVolume = 10000L;
        AtomicLong totalOps = new AtomicLong(0);

        System.out.println("\n▶ [2] Long Scaling(Primitive) 기반 다중 종목 쓰레드 폭풍 유입 시작...");
        long startTime = System.currentTimeMillis();
        long endTime = startTime + (TEST_DURATION_SECONDS * 1000L);

        CountDownLatch startLatch = new CountDownLatch(1);

        for (int i = 0; i < THREAD_COUNT; i++) {
            executor.submit(() -> {
                try {
                    startLatch.await();
                    long localOps = 0;
                    while (System.currentTimeMillis() < endTime) {
                        // 0~99 난수 종목에 접근 (락 경합 분산)
                        int targetSymbolIndex = (int) (Math.random() * SYMBOL_COUNT);
                        store.get(targetSymbolIndex).accumulateAndGet(tickVolume, Math::addExact);
                        localOps++;
                    }
                    totalOps.addAndGet(localOps);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
        }

        startLatch.countDown();
        executor.shutdown();
        while (!executor.isTerminated()) {
            Thread.sleep(100);
        }

        return totalOps.get();
    }
}
