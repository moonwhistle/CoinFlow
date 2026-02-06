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

## 5. 구현 상세 및 기술적 의사결정 (Implementation Strategy)

### 5.1 트레이드 오프 분석 (Trade-off Analysis)

이번 구현에서 선택한 주요 기술적 결정들과 대안 비교입니다.

| 결정 항목 | 선택한 방식 | 대안 및 고려사항 | 이유 (Reasoning) |
| :--- | :--- | :--- | :--- |
| **Data Transfer Object** | **Java Record** | Lombok `@Data` / POJO | - **불변성(Immutability)**: 데이터 전송 중 변경 방지.<br>- **간결성**: 보일러플레이트 코드 제거.<br>- **DTO 적합성**: 단순히 데이터를 나르는 객체로서의 역할에 충실. |
| **Event Propagation** | **Redis Pub/Sub** | Kafka / Redis Stream | - **실시간성**: 1분 봉 마감 즉시 Frontend에 알려야 함 (Fire-and-Forget).<br>- **복잡도**: Kafka에 비해 설정과 운영이 훨씬 가벼움.<br>- **데이터 특성**: '보정' 데이터이므로, 유실 시 다음 보정이나 API 조회를 통해 복구 가능하므로 Persistence가 필수는 아님. |
| **Serialization** | **Jackson** | Gson / Protobuf | - **표준**: Spring Boot의 기본 라이브러리로 호환성 우수.<br>- **확장성**: 추후 다양한 포맷 지원 용이.<br>- **Record 지원**: 최신 버전에서 Record 자동 지원. |
| **Broadcasting** | **WebSocket Direct** | Polling | - **Latency**: 초단위 틱 데이터 전송에 Polling은 부하가 큼.<br>- **효율**: 연결된 세션에만 Push하는 방식이 서버 리소스 효율적. |

### 5.2 구현 상세 (Implementation Details)

#### 전체 데이터 흐름도

```text
[Producer Layer]
Exchange (WebSocket)
    |
    | (Raw Tick)
    v
[Redis Stream: tick:raw] ------------------------------------------------+
    |                                                                    |
    | (Consumer Group A)                                                 | (Consumer Group B)
    v                                                                    v
[Consumer App]                                                   [WS Gateway]
    |                                                                    |
    +-- (Aggregate) --> [OhlcAccumulator]                                +-- (1. Consume Tick) --> [TickRawStreamConsumer]
    |                         |                                          |                               |
    |                         +-- (Flush 1m) --> [(Database)]            |                               +-- (2. Convert) --> [TickDto (Record)]
    |                         |                                          |                               |
    +-- (Publish Event) --> [Redis Pub/Sub: candle:closed]               |                               +-- (3. Broadcast) --> [WS Client]
                                  |                                      |
                                  | (4. Subscribe)                       |
                                  +------------------------------------> [CandleClosedStreamConsumer]
                                                                         |
                                                                         +-- (5. Broadcast Correction) --> [WS Client]

[Frontend]
WS Client
    |
    +-- (Real-time Update) --> [Chart UI]
    |
    +-- (Correction Update) --> [Chart UI]
```

#### 주요 컴포넌트 역할

1.  **TickRawStreamConsumer (Fast-Path)**:
    *   Redis Stream에서 데이터를 읽자마자 비즈니스 로직 없이 `TickDto`로 변환하여 즉시 클라이언트로 전송합니다.
    *   레이턴시를 최소화하기 위해 DB 작업 등을 배제했습니다.
2.  **Ohlc1mFlushService**:
    *   1분 봉이 완성되어 DB에 저장되는 시점(정본 데이터 확정)에 `CandleClosedEvent`를 발행합니다.
    *   이 이벤트는 '데이터 일관성'을 맞추기 위한 기준점이 됩니다.
3.  **CandleClosedStreamConsumer**:
### 5.3 WebSocket Multiplexing Strategy (Single Connection)

클라이언트의 리소스 효율성을 위해 **"단일 연결, 다중 소스(Single Connection, Multi-Source)"** 방식을 채택했습니다.

*   **Client View**: `/ws/v1/coinflow` 엔드포인트 하나에만 연결하면 됩니다.
*   **Server View**: 하나의 세션(Session)에 대해 여러 컴포넌트가 데이터를 주입합니다.

```text
[Shared Component]
+--------------------------------+
|   SubscriptionSessionManager   | <---+ (1. Get Subscribers)
+--------------------------------+     |
| - subscribers: Map<Sym, Set>   |     |
| + getSubscribers(symbol)       |     |
+--------------------------------+     |
         ^                             |
         | (2. Get Subscribers)        |
         |                             |
+--------+---------------------+  +----+------------------------+
|  CandleClosedStreamConsumer  |  |    TickRawStreamConsumer    |
+------------------------------+  +-----------------------------+
| + onMessage(CandleClosed)    |  | + onMessage(RawTick)        |
| + broadcast(Correction)      |  | + broadcast(TickDto)        |
+--------+---------------------+  +----+------------------------+
         |                             |
         | (Push CandleClosedEvent)    | (Push TickDto)
         |                             |
         v                             v
+---------------------------------------------------------------+
|                    WebSocketSession (Client)                  |
+---------------------------------------------------------------+
```

*   **Shared Session Manager**: `SubscriptionSessionManager` 싱글톤 빈을 통해 구독 정보를 공유합니다.
*   **Multiplexing**: 
    1.  `TickRawStreamConsumer`는 실시간 Tick 발생 시 해당 심볼 구독자를 찾아 전송.
    2.  `CandleClosedStreamConsumer`는 봉 마감 시 해당 심볼 구독자를 찾아 보정 데이터 전송.
    3.  따라서 클라이언트는 별도의 연결 없이 두 종류의 메시지를 모두 수신합니다.
