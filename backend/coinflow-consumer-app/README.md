# CoinFlow Consumer 로직 분석

## 1. 로직 흐름 (Tick 데이터 수신부터 집계까지)

이 시스템은 Redis Stream 기반으로 바이너리 Tick 데이터를 소비하고, 서버 메모리에서 1m/5m/30m Kline을 집계한 뒤 Redis Pub/Sub, Redis Live Cache, 비동기 DB 저장, Batch ACK를 조율하는 구조입니다.

### A. 수신 단계 (Ingestion)
1. **Source**: Redis Stream (`tick:raw`)
2. **Consumer**: `TickRawEventConsumer` (`StreamListener` 구현체)
3. **Handler**: `TickRawMessageHandler`가 `Map<String, byte[]>`에서 바이너리 payload를 꺼내 `TickRawBinaryCodec`으로 필드를 직접 추출
4. **Service**: `TickProcessService`가 `KlineAggregatorService`에 집계를 위임하고 후속 전파/저장/ACK 흐름을 조율

### B. 집계 단계 (Aggregation - In-Memory)
- **Store**: `KlineAggregatorService`가 `symbol:interval` 키 기준으로 `KlineState`를 관리
- **Accumulator**: `KlineState`와 `MutableKlineSnapshot`이 메모리 상에서 OHLC 데이터를 갱신
    - **로직**:
        - `Open`: 최초 수신 가격
        - `High/Low`: 최대/최소 가격 비교 갱신
        - `Close`: 가장 늦은 `eventTime`을 가진 가격으로 결정
        - `Volume`: 누적 합계
    - **동시성**: `ConcurrentHashMap` 기반 상태 저장소를 사용하고, 최근 마감 버킷은 `recentlyClosed` 버퍼에서 Late Tick 보정을 처리

### C. 지속성 및 ACK 단계 (Persistence & ACK)
- **Live Snapshot**: 진행 중인 Kline은 Redis Live Cache에 저장되고 Redis Pub/Sub으로 브로드캐스트됩니다.
- **Closed / Late Snapshot**: 마감 또는 Late Tick 보정 스냅샷은 `DbPersistService.persistClosedCandleAsync()`를 통해 비동기로 DB에 저장됩니다.
- **Batch ACK**:
    1. 저장 파이프라인이 완료되면 `BatchAckWorker.addAck(recordId)` 호출
    2. `BatchAckWorker`가 500건 또는 50ms 기준으로 Redis Stream XACK를 묶어서 처리
    3. RecordId 기반 Caffeine 캐시로 재전달 메시지의 중복 집계를 방지

## 2. 패키지 및 의존성 분석

모듈 구조는 관심사를 잘 분리하고 있으나, Core 모듈과 다소 강한 결합이 있습니다.

- **`com.coinflow.consumer`**: Redis 연결 관련 코드를 깔끔하게 분리
- **`com.coinflow.aggregation`**: Consumer 파이프라인 조율, Redis Pub/Sub 전파, 비동기 저장, Batch ACK 로직 포함
- **Dependencies**:
    - **`:coinflow-core`**: 
        - 도메인 엔티티(`Symbol`, `Ohlc`)와 서비스(`SymbolService`, `KlineAggregatorService`, `Ohlc1mService`, `Ohlc5mService`, `Ohlc30mService`)를 사용
        - **관찰**: Consumer 애플리케이션이 Core 도메인 서비스를 직접 의존합니다. 모듈러 모놀리스(Modular Monolith) 구조에서는 일반적이나, 트랜잭션/엔티티 구조에 직접 묶여 있습니다.
    - **`:coinflow-common`**: Event, Metric, Tick binary codec, validation 공통 코드 사용

## 3. Core 모듈 통합 분석 (Persistence & Upsert)

`Ohlc1mService.java` 분석 결과, 다음과 같은 방식으로 데이터 일관성을 유지합니다.

- **Upsert 전략**: `repository.findBySymbolIdAndBucketTime`으로 조회 후, 없으면 생성(`orElseGet`)하고 있으면 기존 데이터에 병합(`candle.apply`)하는 **Read-Modify-Write** 패턴을 사용합니다.
- **지연 이벤트 처리 (Late Event Handling)**:
    - Flush 이후에 도착한(지각한) 틱 데이터가 와도, DB에 있는 기존 봉(Candle)을 조회하여 값을 갱신(High/Low/Close/Volume)하므로 데이터 정합성이 깨지지 않습니다.
- **동시성 고려사항**:
    - `AbstractOhlc`에 JPA 낙관적 락(`@Version`)이 적용되어 있습니다.
    - 동일 심볼+버킷은 유니크 인덱스와 낙관적 락 기반으로 중복 저장 및 Lost Update 위험을 방어합니다.

## 4. 개선 제안 (Suggestions for Improvement)

### A. 인메모리 상태 확장성
- **현재**: `KlineAggregatorService`가 지원 interval별 `KlineState`와 최근 마감 버퍼를 메모리에 유지함
- **위험**: 심볼 수가 크게 증가하면 상태 맵과 최근 마감 버퍼의 메모리 사용량이 증가할 수 있음
- **개선**: 심볼 확장 시 상태 보관 정책, 버퍼 TTL, interval별 eviction 정책을 별도로 검토 필요

### B. Hot Symbol에 대한 경합 (Contention)
- **현재**: 단일 hot symbol에 Tick이 집중되면 해당 symbol/interval의 `KlineState` 갱신이 집중됨
- **위험**: 틱 빈도가 매우 높은(예: 1000+ TPS) 심볼의 경우, 락 경합이 병목지점이 될 수 있음
- **개선**: 
    - Volume 집계 등에 **Double Buffering** 또는 **LongAdder** 도입
    - 또는 내부적으로 Kline 상태를 샤딩(Sharding)하여 경합 분산

### C. 메모리 안전성
- **현재**: DB 저장 실패 시 재시도 후 Batch Reconciliation으로 복구하는 방향을 가짐
- **위험**: 장시간 DB 장애가 발생하면 비동기 저장 큐와 Redis Stream PEL이 증가할 수 있음
- **개선**: **Backpressure** 메커니즘 도입 또는 저장 큐/PEL 모니터링 기반 소비 일시 정지 정책 검토 필요
