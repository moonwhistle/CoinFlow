# # CoinFlow Consumer 로직 분석

## 1. 로직 흐름 (Tick 데이터 수신부터 집계까지)

이 시스템은 스케줄링된 Flush 메커니즘을 가진 **스트림 기반 인메모리 집계(Stream-based In-Memory Aggregation)** 패턴을 따릅니다.

### A. 수신 단계 (Ingestion)
1. **Source**: Redis Stream (`tick:raw`)
2. **Consumer**: `TickRawEventConsumer` (`StreamListener` 구현체)
3. **Handler**: `TickRawMessageHandler`가 raw Map 데이터를 `TickRawEvent` 객체(심볼, 가격, 수량, 시간)로 변환
4. **Service**: `TickProcessService`가 구체적인 집계 서비스(`Ohlc1mAggregationService`)로 처리를 위임

### B. 집계 단계 (Aggregation - In-Memory)
- **Store**: `Ohlc1mAggregationStore`가 `ConcurrentHashMap<AggregateKey, OhlcAccumulator>`를 관리
- **Accumulator**: `OhlcAccumulator`가 메모리 상에서 OHLC 데이터를 갱신
    - **로직**:
        - `Open`: 최초 수신 가격
        - `High/Low`: 최대/최소 가격 비교 갱신
        - `Close`: 가장 늦은 `eventTime`을 가진 가격으로 결정
        - `Volume`: 누적 합계
    - **동시성**: `ConcurrentHashMap.compute()`를 사용하여 키(심볼+버킷) 단위로 락을 걸고 안전하게 처리

### C. 지속성 단계 (Persistence - Flush)
- **Scheduler**: `Ohlc1mFlushScheduler`가 **1000ms(1초)** 마다 실행
- **Close Check**: `BucketCloseChecker`를 통해 메모리 상의 모든 키를 순회하며 버킷 시간 윈도우가 지났는지 확인
- **Flush Action**:
    1. `Ohlc1mFlushService.flush()` 호출
    2. `Ohlc1mService.applyAndSave()`를 통해 DB 저장
    3. `Ohlc1mFlushedEvent` 발행 (후속 5m/30m 롤업 처리를 위함)
    4. 메모리에서 해당 키 제거

## 2. 패키지 및 의존성 분석

모듈 구조는 관심사를 잘 분리하고 있으나, Core 모듈과 다소 강한 결합이 있습니다.

- **`com.coinflow.consumer`**: Redis 연결 관련 코드를 깔끔하게 분리
- **`com.coinflow.aggregation`**: 핵심 비즈니스 로직 포함 (Accumulator, Store, Scheduler)
- **Dependencies**:
    - **`:coinflow-core`**: 
        - 도메인 엔티티(`Symbol`, `Ohlc`)와 서비스(`SymbolService`, `Ohlc1mService`)를 사용
        - **관찰**: Consumer 애플리케이션이 Core 도메인 서비스를 직접 의존합니다. 모듈러 모놀리스(Modular Monolith) 구조에서는 일반적이나, 트랜잭션/엔티티 구조에 직접 묶여 있습니다.
    - **`:coinflow-common`**: DTO 및 Event 정의 사용

## 3. Core 모듈 통합 분석 (Persistence & Upsert)

`Ohlc1mService.java` 분석 결과, 다음과 같은 방식으로 데이터 일관성을 유지합니다.

- **Upsert 전략**: `repository.findBySymbolIdAndBucketTime`으로 조회 후, 없으면 생성(`orElseGet`)하고 있으면 기존 데이터에 병합(`candle.apply`)하는 **Read-Modify-Write** 패턴을 사용합니다.
- **지연 이벤트 처리 (Late Event Handling)**:
    - Flush 이후에 도착한(지각한) 틱 데이터가 와도, DB에 있는 기존 봉(Candle)을 조회하여 값을 갱신(High/Low/Close/Volume)하므로 데이터 정합성이 깨지지 않습니다.
- **동시성 고려사항**:
    - JPA의 낙관적 락(`@Version`)이나 별도의 비관적 락이 보이지 않습니다.
    - 만약 동일 심볼+버킷에 대해 여러 Consumer가 동시에 Flush를 시도할 경우, **Lost Update**나 **Unique Constraint Violation**이 발생할 수 있는 구조입니다. (현재는 Redis Stream Group으로 인해 단일 파이프라인 처리가 보장된다면 안전함)

## 4. 개선 제안 (Suggestions for Improvement)

### A. 스케줄러 루프의 확장성
- **현재**: 스케줄러가 매초 **모든 활성 키**를 순회함
- **위험**: 관리하는 심볼 수가 증가(예: 10,000개 이상)하면, 이 전체 순회 방식("Stop-the-world" 스타일)은 성능 저하를 일으킬 수 있음
- **개선**: **DelayQueue**나 시간 윈도우 기반 파티셔닝 맵을 사용하여, 실제로 마감 시간이 된 버킷만 체크하도록 변경 추천

### B. Hot Symbol에 대한 경합 (Contention)
- **현재**: `store.compute()` 메서드는 해당 키에 대해 락을 걺
- **위험**: 틱 빈도가 매우 높은(예: 1000+ TPS) 심볼의 경우, 락 경합이 병목지점이 될 수 있음
- **개선**: 
    - Volume 집계 등에 **Double Buffering** 또는 **LongAdder** 도입
    - 또는 내부적으로 Accumulator를 샤딩(Sharding)하여 경합 분산

### C. 메모리 안전성
- **현재**: Flush 성공에 의존하여 메모리를 비움
- **위험**: DB 장애 등의 이유로 Flush가 계속 실패하면, 메모리 맵이 무한정 커져 OOM(Out Of Memory) 발생 가능
- **개선**: **Backpressure** 메커니즘 도입 또는 `max-size` 제한(오래된 데이터 디스크 스필 또는 소비 일시 정지) 구현 필요
