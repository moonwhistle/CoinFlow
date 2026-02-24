package com.coinflow.aggregation.process.aggregate;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * JVM 힙 메모리를 -Xmx32m 옵션으로 제한하고
 * BigDecimal 누적 시 몇 번의 객체 생성 후 OOM이 폭발하는지 눈으로 보기 위한 프로파일링용 단일 실행 클래스.
 *
 * <p>
 * 실행 방법:
 * IDE Run 설정의 VM Options에 `-Xmx32m` (또는 `-Xmx64m`)을 넣고 Main 메서드 실행
 * </p>
 */
public class BigDecimalGCLimitProfiler {

    public static void main(String[] args) {
        System.out.println("🚀 [BigDecimal 객체 생성 JVM 한계 테스트 시작] 🚀");
        System.out.println("주의: JVM 옵션에 '-Xmx32m'을 주고 실행하세요.\n");

        final BigDecimal tickVolume = new BigDecimal("0.0001");
        BigDecimal accumulator = BigDecimal.ZERO;

        // 메모리에서 즉시 수거(GC)되지 않고 참조를 유지하게 하여 악성 메모리 누수/단편화 시뮬레이션
        // (실제 HashMap에 누적되거나 API 응답으로 잡혀있는 경우와 유사한 압박을 줌)
        List<BigDecimal> historyList = new ArrayList<>();

        long opsCount = 0;
        long startTime = System.currentTimeMillis();

        try {
            while (true) {
                accumulator = accumulator.add(tickVolume);

                // GC를 극단적으로 유발하기 위해 누적된 참조를 List에 보관 (마치 1분봉 데이터가 계속 메모리에 있는 것처럼)
                historyList.add(accumulator);
                opsCount++;

                if (opsCount % 1_000_000 == 0) {
                    System.out.println(String.format("%,d", opsCount) + " 번째 객체 생성 성공 (누적 용량 버팀)");
                }
            }
        } catch (OutOfMemoryError e) {
            long endTime = System.currentTimeMillis();
            System.err.println("\n💥 [OutOfMemoryError 발생!] JVM 힙 메모리 한계 도달 💥");
            System.err.println("원인: BigDecimal 새 객체 무한 할당 및 누적된 참조로 인한 GC 실패");
            System.err.println("버텨낸 총 연산 횟수: " + String.format("%,d", opsCount) + " 번");
            System.err.println("발생 소요 시간: " + (endTime - startTime) + "ms");
            System.err.println("에러 메시지: " + e.getMessage());
        }
    }
}
