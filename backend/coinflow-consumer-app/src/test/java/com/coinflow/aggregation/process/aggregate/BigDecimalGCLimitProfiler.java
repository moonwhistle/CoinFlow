package com.coinflow.aggregation.process.aggregate;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

public class BigDecimalGCLimitProfiler {

    public static void main(String[] args) {
        System.out.println("🚀 [실제 운영 환경(Default Heap) BigDecimal 한계 테스트 시작] 🚀");

        // 현재 JVM에 할당된 최대 메모리(실제 환경 사이즈) 확인
        long maxMemory = Runtime.getRuntime().maxMemory();
        System.out.println("현재 JVM 최대 가용 메모리: " + (maxMemory / 1024 / 1024) + " MB\n");

        final BigDecimal tickVolume = new BigDecimal("0.0001");
        BigDecimal accumulator = BigDecimal.ZERO;

        // 누적되는 틱 데이터(1분봉 등)가 메모리에 상주하는 상황을 모방
        List<BigDecimal> historyList = new ArrayList<>();

        long opsCount = 0;
        long startTime = System.currentTimeMillis();

        try {
            while (true) {
                accumulator = accumulator.add(tickVolume);
                historyList.add(accumulator);
                opsCount++;

                // 500만 번마다 현재 메모리 상태 출력
                if (opsCount % 5_000_000 == 0) {
                    long usedMemory = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory();
                    System.out.println(String.format("%,d", opsCount) + " 개 생성 중... (현재 사용 메모리: "
                            + (usedMemory / 1024 / 1024) + " MB)");
                }
            }
        } catch (OutOfMemoryError e) {
            long endTime = System.currentTimeMillis();
            System.err.println("\n💥 [OutOfMemoryError 발생!] 메모리가 한계에 도달하여 터졌습니다 💥");
            System.err.println("할당되었던 최대 메모리: " + (maxMemory / 1024 / 1024) + " MB");
            System.err.println("버텨낸 총 연산 횟수(생성된 인스턴스 수): " + String.format("%,d", opsCount) + " 개");
            System.err.println("서버 다운까지 걸린 시간: " + (endTime - startTime) + "ms");
        }
    }
}
