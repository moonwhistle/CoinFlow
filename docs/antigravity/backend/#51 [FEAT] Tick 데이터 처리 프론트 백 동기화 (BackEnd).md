# #51 [FEAT] Tick 데이터 처리 프론트/백 동기화 (BackEnd)

## 1. 개요 (Overview)
실시간 차트 서비스에서 Tick 데이터를 안정적으로 처리하고, 프론트엔드와의 데이터 정합성을 보장하기 위한 백엔드의 역할을 정의한다.

## 2. 현재 아키텍처 (As-Is Status)

### 2.1 데이터 수집 및 저장 (Ingestion & Persistence)
- **Redis Stream (`tick:raw`)**: 외부 거래소(Upbit 등)나 Market Data System으로부터 Raw Tick 데이터를 수신.
- **Consumer (`TickRawEventConsumer`)**: Stream 데이터를 읽어서 `TickRawMessageHandler`로 전달.
- **Memory Aggregation**: `Ohlc1mAggregationStore` (ConcurrentHashMap)에 1분봉 형태 (`OhlcAccumulator`)로 실시간 누적.
- **Persistence**: `Ohlc1mFlushScheduler`가 1초마다 **"마감된 버킷(Closed Bucket)"**을 DB(`ohlc_1m`)에 저장.
    - **Issue Fixed**: 최근 지연 도착(Late Arrival) 데이터의 덮어쓰기 오루(Corruption) 문제는 `merge()` 로직 도입으로 해결됨.

### 2.2 클라이언트 전송 (Broadcasting)
- **Separate Path**: `coinflow-ws-gateway` 모듈의 `TickRawStreamConsumer`가 동일한 Redis Stream(`tick:raw`)을 구독.
- **Bypass Business Logic**: Consumer App의 집계(Aggregation) 로직을 거치지 않고, Redis에서 꺼낸 즉시 WebSocket 세션으로 브로드캐스트.
    - **장점**: Aggregation 연산 비용 및 DB Flush 지연과 무관하게 즉시 전송됨 (Low Latency).
    - **단점**: 현재 전송되는 데이터는 Raw String(Map) 형태이므로 클라이언트가 받기 편한 DTO 구조로 다듬을 필요가 있음.
- **Flow**: `Redis Stream` -> `Gateway Consumer` -> `WebSocket Session` -> `Client`.

---

## 3. 구현 목표 (To-Be Strategy)

### 3.1 Fast-Path Tick Broadcasting
> *"Tick은 가공 없이 즉시 쏜다."*
- Redis Stream에서 Tick을 꺼내자마자, 비즈니스 로직(DB 저장 등)을 타기 전에 **즉시 WebSocket으로 Broadcast** 하여 Latency를 최소화한다.
- **DTO Optimization**: 불필요한 필드(서버 내부 메타데이터 등)를 제외한 경량화된 `TickDto` 전송.

### 3.2 Data Convergence (Synchronization)
- **Candle Closed Event**:
    - 매 분(00초)이 지날 때마다, 백엔드는 해당 분의 확정된 OHLC 데이터를 `CandleClosedEvent`로 발행.
    - 이는 프론트엔드가 자체 집계한 데이터(Optimistic Data)를 교체(Replace)하여 최종 정합성을 맞추는 기준(Truth)이 된다.

## 4. 작업 목록 (Backend Tasks)
- [ ] **WS Optimization**: Redis Stream Consumer에서 WebSocket Broadcast 경로 최적화 (Latency 최소화).
- [ ] **Candle Close Notifier**: `BucketCloseChecker`가 닫힘을 감지하면 `CandleClosed` 메시지를 WS로 전송하는 로직 구현.
