# 초당 수만 건의 틱 데이터, 거래량(Volume)은 어떻게 집계해야 할까? (BigDecimal vs Long)

안녕하세요, 오늘은 거래량 계산 방법에 대해 소개하고자 합니다!

CoinFlow에서 가장 많이 수신되고, 가장 빈번하게 계산되는 데이터는 체결 틱(Tick)의 **거래량과 가격**입니다.

보통 많은 사람들이 1주를 사면 거래량도 1개 증가하는 것으로 알고 계시지만, 사실 0.xx 단위로도 거래가 될 수 있습니다.
그렇기에 매 틱마다 들어오는 거래량 소수점 데이터(예: `0.00123456 BTC`)를 1분 봉, 5분 봉으로 쉴 새 없이 더해야 합니다.

## Option 1: Double (부동 소수점) - 빠르지만 위험한 선택
* **장점(직접적인 하드웨어 지원):**
    * **JVM 바이트코드 레벨:** Java 소스코드에서 `double` 타입의 덧셈(`a + b`)을 수행하면, 컴파일 시 `dadd`라는 단일 바이트코드 단위 연산으로 변환됩니다.
    * **JIT 컴파일 및 CPU 실행 레벨:** 런타임에 JIT 컴파일러는 이 `dadd` 명령어를 타겟 CPU의 **하드웨어 부동소수점 연산 장치** 명령어로 직접 매핑합니다. 
    * 즉, 별도의 객체 메모리 할당(`new`)이나 힙 영역 접근 없이 CPU의 전용 레지스터 안에서 즉각적으로 연산이 끝나기 때문에 연산 속도와 CPU 효율 면에서는 다른 방식보다 압도적으로 빠릅니다.

* **단점(IEEE 754 표준 한계):**
    * 컴퓨터는 실수를 이진수 분수합으로 표현하는 **IEEE 754 표준**을 따릅니다. 이로 인해 `0.1`과 같은 단순한 십진수도 이진수로는 무한 소수가 되어 메모리에 저장될 때 미세한 값이 잘려 나갑니다.
    * 결과적으로 `0.1 + 0.2`를 계산하면 `0.30000000000000004`와 같은 오차가 발생합니다. 1분에 수만 건의 틱(Tick) 거래량이 누적되는 금융 시스템에서 이 미세한 오차가 누적되면 결국 **데이터 정합성 문제** 로 이어집니다.

* **테스트:**
  ```java
  // VolumeScalingTest.java 일부
  @Test
  @DisplayName("IEEE 754: Double 타입 단순 연산 시 부동소수점 오차 발생 검증")
  void double_SimpleAddition_FloatingPointError_Test() {
      // Given
      double volume1 = 0.1;
      double volume2 = 0.2;

      // When
      double sum = volume1 + volume2;

      // Then (0.3이 아닌 0.30000000000000004 반환)
      assertThat(sum).isNotEqualTo(0.3);
      assertThat(sum).isEqualTo(0.30000000000000004);
  }
  ```

* **결론:** 금융/트레이딩 시스템에서 해당 `double` 연산은 부적절하다고 판단했습니다.

## Option 2: BigDecimal - 정확하지만 무거운 선택
* **장점:** Java에서 소수점 오차 없이 정확한 연산을 하려면 `BigDecimal`이 제일 정확합니다. 

* **단점(성능 병목):**
  * `BigDecimal`은 객체이기에, 한 번 더할 때마다 새로운 인스턴스가 생성됩니다.
  * 초당 수만 건의 틱이 들어오는 상황에서 매번 `new BigDecimal()`을 만들고 `add()`를 호출하면, **GC 오버헤드**가 발생하여 시스템 성능 저하(STW)가 발생할 수 있습니다.

* **테스트: GC OOM 벤치마크**:
  ```java
  // BigDecimalGCLimitProfiler.java 일부 - JVM Default Heap 한계 측정
  public static void main(String[] args) {
      final BigDecimal tickVolume = new BigDecimal("0.0001");
      BigDecimal accumulator = BigDecimal.ZERO;

      try {
          while (true) {
              accumulator = accumulator.add(tickVolume);
              // 누적되는 틱 데이터가 메모리에 상주하는 상황
              currentChunk[chunkIndex++] = accumulator;
          }
      } catch (OutOfMemoryError e) {
          System.err.println("OutOfMemoryError 발생!");
      }
  }
  ```
  * 힙 메모리를 제한하지 않은 실제 운영 환경과 동일한 4GB 메모리 환경에서, 무한정 `BigDecimal` 누적을 발생시켜 보았습니다.
  * 프로파일링 결과, 초당 수십만 개의 찌꺼기 객체가 생성되며 **9,500만 개의 객체가 누적되는 시점(약 3.8GB)에서 GC가 한계에 다다라 `OutOfMemoryError: Java heap space` 런타임 에러를 뿜으며 서버가 그대로 다운(Freezing)되는 것**을 시각적으로 확인했습니다. 
  * 즉, 틱이 1억 건만 들어와도 서버가 죽는다는 치명적인 약점을 증명했습니다.

* **결론:** DB 저장이나 API 응답용으로는 좋으나, **휘발성 잦은 인메모리 누적 연산용으로는 부적합** 하다고 판단했습니다.

## 해결책: 고정 소수점 기반의 Long Scaling 전략 도입
* **아이디어:** 소수점 위치를 고정시켜 버리면, 소수를 정수처럼 취급해서 매우 빠르고 오차 없이 계산할 수 있지 않을까?
  * 알고리즘 문제를 풀 때 부동소수점 오차를 피하기 위해 특정 배수를 곱해 정수로 치환하여 계산하는 것과 동일한 원리입니다.

* **구현 방법 (VolumeScaler 도입):**
  * `coinflow-core` 모듈 내의 [`VolumeScaler`](../../../coinflow-core/src/main/java/com/coinflow/domain/ohlc/policy/VolumeScaler.java) 정책 클래스를 구현하여 이 과정을 캡슐화했습니다.
  * **Delimiter:** 암호화폐 거래의 대다수는 소수점 8자리까지의 정밀도를 가집니다. 그래서 delimiter를 `10^8 (100,000,000)`로 정해줬습니다.
  * **값이 들어올 때:** 외부 (업비트 등)에서 들어온 거래량 `0.12345678`에 `10^8`을 곱해 순수 정수인 `12345678L(long)`로 변환(`VolumeScaler.toLong`)하여 메모리(`OhlcAccumulator`)에 누적합니다.
  * **연산:** CPU가 가장 잘하고 빠른 기본 자료형인 `long`의 단순 `+` 연산만 수행하므로 GC 부하가 거의 없습니다.(`volume = Math.addExact(volume, vol);`)
  * **값을 내보낼 때:** 1분 봉이 마감되어 DB에 저장하거나 클라이언트(프론트엔드)로 응답을 내려줄 때만 다시 `10^8`로 나누어 `BigDecimal`로 복원합니다.

* **테스트: 연산 속도 테스트**:
  ```java
  // VolumeScalingTest.java 일부 - 1천만 번 누적 성능 비교
  // 1. BigDecimal 성능 측정
  long startBd = System.currentTimeMillis();
  for (int i = 0; i < iterationCount; i++) {
      accumulatedBd = accumulatedBd.add(bdTickVolume);
  }
  long bdDuration = System.currentTimeMillis() - startBd; // 약 62ms

  // 2. Long 성능 측정
  long startLong = System.currentTimeMillis();
  for (int i = 0; i < iterationCount; i++) {
      accumulatedLong = Math.addExact(accumulatedLong, longTickVolume);
  }
  long longDuration = System.currentTimeMillis() - startLong; // 약 4ms
  
  assertThat(longDuration).isLessThan(bdDuration);
  ```
  * 1,000만 번의 누적 연산을 수행한 결과, `BigDecimal`은 **약 62ms**가 소요된 반면, 락 경합이 없는 `Long` 연산은 **단 4ms**만에 처리를 완료했습니다. 
  * 현재 우리 시스템의 Redis Stream 컨슈머는 **단일 쓰레드 파이프라인**으로 동작하며 락 경합 리소스 오버헤드가 없으므로, **Long 도입만으로 순수 처리 대역폭(역량)이 15배 향상**되는 효과를 얻었습니다.

## 최종 분석
* **성능:** 무거운 `BigDecimal` 객체 생성과 불완전한 `double` 연산 대신, `long`의 단순 `+` 연산만 수행하는 방식으로 성능 최적화를 구현했습니다.
* **Overflow 방어:** 만약 특정 코인의 폭발적 거래로 거래량이 `long`의 최대값(약 922경)을 초과할 경우를 대비해 방어 로직을 구성해봤습니다.
  * **단순 `+` 연산의 위험성:** Java에서 두 `long` 값의 합이 범위를 넘어가면 시스템은 예외를 던지지 않고 오버플로우로 인해 음수로 바뀌어버립니다. 이는 금융/트레이딩 시스템에서 치명적인 문제라고 생각했습니다.
  * **`Math.addExact()` 적용:**
    ```java
    // OhlcAccumulator.java 일부
    public synchronized void apply(BigDecimal price, long vol, Instant eventTime) {
        // ... 가격, 시간 갱신 로직 생략 ...

        // volume overflow 검증 및 누적
        volume = Math.addExact(volume, vol);
    }
    ```
    단순 `+` 대신 `Math.addExact()` 연산을 사용하여 오버플로우 발생 시 즉시 `ArithmeticException`을 던지도록처리했습니다. (거래량이 992경을 넘지는 않을 것으로 생각하고 구현했습니다.)

## ⚖️ 비즈니스 컨텍스트 및 최종 트레이드오프 분석
기술적인 장단점을 넘어, **"우리의 비즈니스(CoinFlow 트레이딩 라우터)에 어느 방식이 가장 적합한가?"** 에 대한 종합적인 결론입니다.

* **정확도 (1원 단위 오차 허용 불가):** 암호화폐 체결 로직에서 수만 건의 틱 누적 시 발생하는 미세한 IEEE 754 부동소수점 오차는 고객 자산 정합성에 치명적이므로 `double`은 완전 배제했습니다.
* **성능 및 지연 (Latency의 중요성):** 트레이딩 시스템에서 성능 병목은 곧 실시간 차트의 렉(Lag)과 체결 지연을 의미합니다. 무거운 객체 생성을 동반하는 `BigDecimal`은 초당 수만 건 파이프라인에서 치명적인 GC 오버헤드(STW)를 유발함을 프로파일러를 통해 교차 검증했습니다. 반면 `Long` 기반 연산은 대역폭을 15배 향상 시킵니다.
* **장기 운영 안정성 (메모리 OOM 관점):** 장기간 중단 없이 돌아가야 하는 서버에서, 메모리에 쌓이는 가비지(Garbage) 객체는 시한폭탄과 같습니다. 객체 할당(Allocation) 자체가 0건인 `Long Scaling` 방식은 OOM 위험을 구조적으로 제거합니다.
* **운영 복잡성 극복:** `Long`을 사용할 때 가장 큰 리스크인 한계치(992경) 오버플로우 문제는 `Math.addExact`를 통한 Fail-fast 예외 처리 방어 로직으로 커버할 수 있습니다. 

**결론적으로, 다소의 타입 변환 컨버팅 복잡성(`VolumeScaler` 관리)을 감수하더라도, '객체 생성 제로(Zero Allocation)'를 통한 극단적 수준의 장기 메모리 안정성과 초저지연(Latency) TPS 확보를 위해 `Long Scaling` 아키텍처 도입을 최종 표준 지침으로 채택했습니다.**
