# #49 [DOCS] Backend 개선사항 정리 (Single-Server Focus)

## 1. 개요 (Overview)
- **목적**: Single-Server 아키텍처 환경에서 극한의 성능과 안정성을 추구한다.
- **방향성**: 분산 시스템의 복잡성을 배제하고, **CS(OS, Network, Data Structure)** 기본기와 **JVM 내부 동작** 원리에 집중하여 하드웨어 자원을 100% 활용하는 고성능 백엔드를 구축한다.

---

## 2. 주요 개선 영역 (Priority & Deep Dive)

### 2.1 [Priority 1] 1m OHLC Algorithm Modification (Persistence & Consistency)
> *"현재의 구조는 사용자가 '새로고침' 했을 때 최신 1분의 데이터를 보여주지 못한다."*

#### 📌 Context / Current Status
- **Aggregation**: Tick 데이터가 들어오면 `ConcurrentHashMap`(`Ohlc1mAggregationStore`)에 메모리 상에서 누적(Accumulate)된다. (`compute` 메서드로 Atomic 처리)
- **Flush Policy**: `Ohlc1mFlushScheduler`가 1초마다 돌지만, **`isOpen()`(아직 시간이 지나지 않은 버킷)인 경우 Flush를 건너뛴다.**
    ```java
    // Ohlc1mFlushScheduler.java
    if (bucketCloseChecker.isOpen(INTERVAL, key.bucket())) {
        continue; // <--- This causes the Data Gap!
    }
    ```
- **Problem (Data Gap)**:
    - 사용자가 12:00:30에 API를 요청하면 DB에 저장된 11:59:00까지의 데이터만 반환된다.
    - WebSocket은 12:00:30 이후의 실시간 Tick만 보낸다.
    - 결과적으로 **12:00:00 ~ 12:00:29 사이의 30초 데이터가 차트에서 소실**된다.

#### 🚀 Optimization Strategy
- [ ] **Write-Behind Caching**: 메모리의 Accumulator 상태를 '현재 진행 중(Current)' 상태로 간주하고, API 요청 시 **DB(Historical) + Memory(Real-time)** 데이터를 병합(Merge)하여 반환하도록 개선.
- [ ] **Periodic Snapshot Flush**: 버킷이 닫히지 않았더라도 일정 주기(예: 1초)마다 DB에 `UPSERT` 하도록 변경하여 데이터 내구성(Durability) 확보.

---

### 2.2 Concurrency & Synchronization (CS: OS/Thread)
> *"멀티 코어를 100% 활용하면서 데이터 정합성을 어떻게 지킬 것인가?"*

#### 📌 Context / Current Status
- **Current**: `ConcurrentHashMap.compute()`를 사용하여 Key(Symbol+Time) 단위의 Locking을 수행 중. 비교적 효율적이나, 높은 오버헤드가 발생할 수 있음.
    ```java
    // BaseOhlcAggregationStore.java
    store.compute(key, (k, acc) -> {
        // Locked Region (Atomic)
        acc.apply(event.price(), volume, event.eventTime());
        return acc;
    });
    ```
- **Problem**: 수천 개의 Tick이 동시에 몰릴 때 `compute` 블록 내부의 연산이 길어지면 해당 Key에 대한 Lock Contention(경합)이 발생하여 CPU Context Switch 비용 증가.

#### 🚀 Optimization Strategy
- [ ] **Lock-Free/Wait-Free**: `OhlcAccumulator` 내부 필드를 `LongAdder`(Volume)나 `AtomicReference`(Price)로 변경하여 CAS(Compare-And-Swap) 기반의 Non-blocking 알고리즘 적용.
- [ ] **Lock Granularity**: `synchronized` 블록 범위를 최소화하거나 `StampedLock`(Read/Write 분리 및 Optimistic Read 지원) 도입 검토.

---

### 2.3 Data Structures & Memory (CS: Data Structure)
> *"메모리 낭비 없이 데이터를 가장 빠르게 조회/수정하는 구조는?"*

#### 📌 Context / Current Status
- **Current**: Java의 기본 `ArrayList`, `HashMap` 등을 사용하며, 모든 Tick을 객체(`TickRawEvent`)로 래핑하여 힙 메모리에 할당.
- **Problem**:
    - **Object Overhead**: Java 객체 헤더(12~16 bytes)로 인해 실제 데이터(double price, long volume)보다 더 큰 메모리를 소비.
    - **GC Pressure**: 초당 수천 개의 Tick 객체가 생성되고 버려짐(Young Gen GC 빈번 발생).

#### 🚀 Optimization Strategy
- [ ] **Primitive Collections**: `Eclipse Collections`나 `Trove` 라이브러리를 사용하여 Boxing/Unboxing 오버헤드 제거.
- [ ] **Ring Buffer (Disruptor)**: LMAX Disruptor 패턴을 적용하여 GC 없이 배열(Array)을 재사용하는 구조로 변경.

---

### 2.4 JVM Performance & Tuning (Language Internals)
> *"자바가 느리다는 편견을 깨는 Low Latency 튜닝"*

#### 📌 Context / Current Status
- **Current**: Default JVM 설정 사용 중. G1GC가 적용되어 있을 가능성이 높음.
- **Problem**: 
    - 실시간 차트에서는 **Tail Latency**(상위 99% 지연 시간)가 치명적임.
    - Stop-The-World(STW)가 100ms만 발생해도 시세가 튀는 현상 발생 가능.

#### 🚀 Optimization Strategy
- [ ] **GC Tuning**: Short-lived 객체가 많은 특성을 고려해 **Young Generation 크기를 늘려** 조기 승격(Premature Promotion) 방지.
- [ ] **JIT Watch**: Hot Spot 코드가 제대로 인라인(Inlining) 처리되는지, Escape Analysis가 동작하여 객체가 스택에 할당되는지 확인.

---

### 2.5 WebSocket & I/O (CS: Network/OS)
> *"Blocking I/O의 한계를 넘어서"*

#### 📌 Context / Current Status
- **Current**: Spring Boot 내장 Tomcat(Worker Thread Pool) 모델 사용. 클라이언트 1명당 스레드 1개가 할당될 수 있음(Blocking Mode일 경우).
- **Problem**: 동시 접속자 수가 늘어나면 스레드 개수 폭증 -> Context Switch 급증 -> 서버 마비.

#### 🚀 Optimization Strategy
- [ ] **Netty (Reactor Pattern)**: Spring WebFlux 또는 Netty 직접 연동을 통해 **Event Loop** 기반의 Non-blocking I/O로 전환. 소수의 스레드로 수만 개의 연결 처리.

---

### 2.6 Batch Processing & Database Tuning (Persistence)
> *"초당 수만 건의 데이터가 DB로 흘러들어갈 때의 병목 해결"*

#### 📌 Context / Current Status
- **Current**: `Ohlc1mFlushScheduler`가 루프를 돌며 각 심볼/버킷마다 `repository.save()`를 개별적으로 호출.
    ```java
    // Ohlc1mFlushScheduler.java
    for (AggregateKey key : store.keysSnapshot()) {
        flushService.flush(key, acc); // <--- N invocations -> N Transactions
    }
    ```
- **Problem**: 
    - **N+1 Query**: 100개 심볼을 Flush하면 100번의 DB Connection/Transaction이 발생.
    - **I/O Overhead**: DB 왕복 비용(Round Trip Time)이 누적되어 전체 시스템 Throughput 저하.

#### 🚀 Optimization Strategy
- [ ] **JDBC Batch Update**: JPA의 `saveAll()`이나 JDBC Template의 `batchUpdate()`를 사용하여 여러 건의 데이터를 한 번의 Network Call로 처리.
- [ ] **Flush Strategy**: 일정 개수(예: 1000개)나 일정 시간(예: 500ms) 단위로 묶어서 처리하는 **Bulk Insert** 도입.

---

## 3. Action Plan (Priority)
1.  **[Immediate] 1m OHLC Data Gap Fix**: API 요청 시 Memory Store 데이터 병합 로직 구현.
2.  **Concurrency Optimization**: `OhlcAccumulator`를 Lock-Free 구조로 리팩토링.
3.  **Benchmark**: JMH(Java Microbenchmark Harness)를 도입하여 기존 코드 vs 개선 코드 성능 비교 수치 확보.
