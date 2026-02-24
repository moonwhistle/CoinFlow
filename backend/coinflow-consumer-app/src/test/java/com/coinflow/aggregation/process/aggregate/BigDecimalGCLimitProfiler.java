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

        // JVM Argument 또는 Default Value 설정 (PR 피드백 반영: 파라미터화)
        // 사용 예: java -Dchunk.size=100000 -Dchunk.limit=2000 BigDecimalGCLimitProfiler
        int chunkSize = Integer.parseInt(System.getProperty("chunk.size", "100000"));
        int chunkLimit = Integer.parseInt(System.getProperty("chunk.limit", "2000"));
        System.out.println("설정된 Chunk Size: " + chunkSize + ", Chunk Limit: " + chunkLimit);

        final BigDecimal tickVolume = new BigDecimal("0.0001");
        BigDecimal accumulator = BigDecimal.ZERO;

        // 누적되는 틱 데이터(1분봉 등)가 메모리에 상주하는 상황을 모방
        // ArrayList.add()의 배열 복사(Arrays.copyOf) 오버헤드를 완전히 제거하기 위해
        // 단위 청크(Chunk) 배열을 할당하여 순수 BigDecimal 객체 생성 부하만 측정합니다.
        List<BigDecimal[]> historyChunks = new ArrayList<>(chunkLimit);
        BigDecimal[] currentChunk = new BigDecimal[chunkSize];
        int chunkIndex = 0;

        long opsCount = 0;
        long startTime = System.currentTimeMillis();

        try {
            while (true) {
                accumulator = accumulator.add(tickVolume);
                currentChunk[chunkIndex++] = accumulator;
                opsCount++;

                // 청크가 꽉 차면 보관함에 넣고 새 청크 할당
                if (chunkIndex == chunkSize) {
                    historyChunks.add(currentChunk);
                    // OOM을 발생시키는 것이 목적이므로 limit을 넘어가면 List에 계속 누적되지만,
                    // ArrayList 자체의 capacity 증가 부하를 줄이기 위해 초기 사이즈를 limit으로 잡은 구조
                    currentChunk = new BigDecimal[chunkSize];
                    chunkIndex = 0;
                }

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
