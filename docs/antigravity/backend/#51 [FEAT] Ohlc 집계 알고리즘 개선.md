# #51 [FEAT] Ohlc 집계 알고리즘 개선

## 1. 개요 (Overview)
현재 구현된 Tick 기반 OHLC 집계 로직의 구조를 상세히 분석하고, 실시간성(Real-time consistency)과 데이터 정합성(Consisteny) 문제를 해결하기 위한 개선 방안을 정의한다.

---

## 2. 현재 상태 분석 (As-Is)

### 2.1 Tick 데이터 집계 구조 (Architecture)
> Tick 데이터는 Redis Stream을 통해 Consumer로 유입되며, 메모리 내 `ConcurrentHashMap`에 1차 집계된 후 DB로 Flush 된다.

**Data Flow:**
1.  **Ingestion**: `TickRawEventConsumer`가 Redis Stream(`tick:raw`)에서 메시지를 수신.
2.  **Processing**: `TickRawMessageHandler` -> `TickAggregationProcessor`를 거쳐 각 시간대별(1m, 5m, 30m) Store로 분기.
3.  **Aggregation (Memory)**: `Ohlc1mAggregationStore` (ConcurrentHashMap)에서 실시간 누적.
4.  **Persistence (DB)**: `Ohlc1mFlushScheduler`가 1초마다 실행되지만, **"마감된 버킷(Closed Bucket)"**만 DB에 저장(`Ohlc1mService.applyAndSave`).

### 2.2 Tick -> 1m OHLC 생성 로직 (Logic Detail)
메모리 상에서 Tick이 어떻게 하나의 캔들(Candle)로 합쳐지는가?

- **Class**: `OhlcAccumulator.java`
- **Key**: `AggregateKey` (Symbol ID + Bucket Time, ex: `BTC_12:00:00`)
- **Fields**:
    - `Open`: 버킷의 첫 번째 Tick 가격.
    - `High`: `Math.max(currentHigh, newTickPrice)`
    - `Low`: `Math.min(currentLow, newTickPrice)`
    - `Close`: 가장 늦은 `eventTime`을 가진 Tick의 가격. (Late Arrival 보정 로직 포함)
    - `Volume`: `Math.addExact(currentVol, newTickVol)` (누적 합산)

### 2.3 관련 주요 로직 및 문제점 (Current Issues)

#### A. Flush Policy의 허점 (Data Gap)
- **Code**: `Ohlc1mFlushScheduler.java`
    ```java
    if (bucketCloseChecker.isOpen(INTERVAL, key.bucket())) {
        continue; // 진행 중인 버킷은 저장하지 않음
    }
    ```
- **Issue**: API 요청 시점(예: 12:00:30)에 DB에는 11:59분 데이터까지만 존재. 12:00:00~12:00:30 데이터는 메모리에만 있고 반환되지 않음. **(사용자 차트 끊김 발생)**

#### B. Concurrency Overhead
- **Code**: `BaseOhlcAggregationStore.java`
    ```java
    store.compute(key, (k, acc) -> {
        acc.apply(...); // Locked Region
        return acc;
    });
    ```
- **Issue**: `compute` 메서드는 해당 Key에 대해 Lock을 건다. 초당 수천 개의 Tick이 동일 심볼에 몰릴 경우 Lock Contention 발생 가능성.

---

## 3. 개선 방향 (To-Be)

### 3.1 [Core] Memory + DB Merge Strategy
> API 요청 시, DB의 과거 데이터와 메모리의 현재 데이터를 병합하여 반환한다.

1.  **API Service 수정**:
    - `Ohlc1mService.findCandles()` 호출 시,
    - DB에서 `[Start, End-1]` 범위 조회.
    - Memory Store(`Ohlc1mAggregationStore`)에서 `End`(현재 진행 중인 버킷) 조회.
    - 두 결과를 List로 합쳐서 반환.

2.  **Snapshot Flush 도입**:
    - 서버 재시작 시 데이터 손실을 방지하기 위해, 진행 중인 버킷도 주기적(1초)으로 DB에 `UPSERT` 한다.

### 3.2 Lock-Free Optimization (Optional)
- `OhlcAccumulator`를 `AtomicReference`와 `LongAdder`를 사용한 Lock-Free 구조로 변경하여 `compute` 락 제거 검토.

---

## 4. 작업 계획 (Action Plan)
1.  **문서화**: 현재 로직 분석 완료 및 문서 작성. (완료)
2.  **구현**: `Ohlc1mService`에 Memory Store 조회 로직 주입 및 Merge 구현.
3.  **검증**: API 호출 테스트를 통해 Data Gap(누락된 30초) 해결 확인.

## 5. OhlcAccumulator 동시성 테스트 리포트 (Concurrency Test Report)

**테스트 목적**: `OhlcAccumulator` 객체가 멀티스레드 환경에서 데이터 유실 없이 안전하게 집계되는지 검증.

### 5.1 테스트 방법론 (Methodology)
- **환경**: JUnit 5, ExecutorService (FixedThreadPool)
- **부하 조건**:
    - **Thread Count**: 10개
    - **Action per Thread**: 1,000회 Accumulate 실행
    - **Total Operations**: 10,000회
- **검증 대상**: `volume` 필드 (단순 덧셈 연산으로 Race Condition 확인이 가장 용이함)

### 5.2 테스트 코드 (Snippet)
```java
@Test
void accumulateVolume_Concurrency_Test() throws InterruptedException {
    // ... setup ...
    int threadCount = 10;
    int additionsPerThread = 1000;
    
    // 10개의 스레드가 동시에 같은 accumulator 객체를 업데이트
    for (int i = 0; i < threadCount; i++) {
        executorService.submit(() -> {
            for (int j = 0; j < additionsPerThread; j++) {
                accumulator.apply(price, 1L, time);
            }
        });
    }
    // ... wait and assert ...
    assertEquals(10000L, accumulator.getVolume());
}
```

### 5.3 테스트 결과 (Results)

#### 1차 시기: 동시성 제어 미적용 (Failure)
- **상태**: `synchronized` 키워드 없음.
- **결과**: **실패 (Failed)**
- **로그**:
    ```
    OhlcAccumulatorTest > Concurrency: accumulate volume correctly in multi-threaded environment FAILED
    expected: <10000> but was: <8542>
    ```
- **분석**: 여러 스레드가 동시에 `volume` 값을 읽고 쓰는 과정에서 연산이 덮어씌워져 데이터 유실 발생 (Lost Update Problem).

#### 2차 시기: 동시성 제어 적용 (Success)
- **조치**: `apply` 메서드에 `synchronized` 키워드 선언.
    ```java
    public synchronized void apply(BigDecimal price, long vol, Instant eventTime) { ... }
    ```
- **결과**: **성공 (Passed)**
- **로그**:
    ```
    OhlcAccumulatorTest > Concurrency: accumulate volume correctly in multi-threaded environment PASSED
    ```
- **결론**: `synchronized`를 통해 모니터 락(Monitor Lock)을 획득한 하나의 스레드만 메서드에 진입하도록 제한함으로써 데이터 정합성 보장.
