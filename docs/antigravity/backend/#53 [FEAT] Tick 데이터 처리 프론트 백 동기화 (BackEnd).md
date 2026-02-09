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

### 5.4 향후 개선 모델: 트래픽 최적화를 위한 구독 분리 (Future Optimization)

트래픽이 증가할 경우, 클라이언트에게 불필요한 데이터(보지 않는 타임프레임의 보정 데이터)를 전송하는 것은 리소스 낭비가 될 수 있습니다.
이를 해결하기 위해 **"구독 분리(Subscription Separation)"** 모델을 도입할 수 있습니다.

**방법 A (Standard - Subscription Separation)**:

1.  **Client Request**: 사용자가 특정 타임프레임(예: 5분 봉)을 선택하면, 프론트엔드는 명시적인 구독 메시지를 보냅니다.
    ```json
    { "command": "SUBSCRIBE", "symbol": "BTC", "interval": "5m" }
    ```
2.  **Server Filtering**: 서버는 해당 소켓 세션에 태그(Tag)를 답니다 (예: `Session#1 -> {BTC, 5m}`).
3.  **Selective Push**: 
    - 1분 봉 마감 이벤트 발생 -> `1m` 구독 세션에게만 전송
    - 5분 봉 마감 이벤트 발생 -> `5m` 구독 세션에게만 전송
4.  **Pros**: 클라이언트는 자신이 요청한 데이터만 수신하므로 네트워크 대역폭과 클라이언트 처리 부하가 최소화됩니다.

---

## 📌 Summary

실시간 Tick 데이터 처리를 위한 프론트엔드/백엔드 동기화 작업을 완료했습니다.
핵심은 **"속도"와 "정확성"이라는 두 마리 토끼를 잡기 위한 이중 경로(Dual-Path) 아키텍처**입니다.

### 🚀 아키텍처 핵심: Dual-Path Strategy

**1. Fast-Path (Redis Stream): 속도 우선 (Optimistic)**
*   **경로**: `Exchange` -> `Redis Stream` -> `WebSocket` -> `Client`
*   **특징**:
    *   데이터베이스 저장이나 복잡한 계산(집계)을 기다리지 않고, 틱(Tick)이 들어오자마자 **0.1초 이내**에 클라이언트로 쏴줍니다.
    *   **"일단 보여준다"**: 중간에 유실될 가능성이 조금 있더라도, 사용자에게 즉각적인 시세 변화를 보여주는 것이 중요하기 때문입니다. (게임의 예측 이동과 유사)

**2. Slow-Path (Redis Pub/Sub): 정합성 보장 (Correction)**
*   **경로**: `Exchange` -> `Aggregator` -> `DB 저장` -> `Redis Pub/Sub` -> `WebSocket` -> `Client`
*   **특징**:
    *   1분마다 모든 데이터를 모아서 DB에 확실하게 저장한 뒤, **"확정된 정답지(CandleClosedEvent)"**를 발행합니다.
    *   **"나중에 고쳐준다"**: 혹시 Fast-Path에서 살짝 틀린 그림을 그렸더라도, 이 경로를 통해 매 분 00초마다 정확한 데이터로 덮어씌워 줍니다.

이 두 가지를 조합하여 사용자는 **"빠르면서도(Fast) 결국엔 정확한(Slow)"** 차트를 보게 됩니다.

## 📚 Changes

### Backend (CoinFlow)
*   **[FEAT] TickDto & CandleClosedEvent Records**: 
    *   기존 Lombok `@Data` 클래스 대신 Java Record를 도입하여 DTO를 경량화하고 불변성을 확보했습니다.
    *   `TickDto`: 실시간 틱 전송용.
    *   `CandleClosedEvent`: 1분 봉 마감 시 확정 데이터 전송용 (`LocalDateTime` -> `String` ISO-8601 직렬화 적용).
*   **[FEAT] WebSocket Multiplexing**:
    *   `CandleClosedStreamConsumer` 구현: Redis Channel(`candle:closed`)을 구독하고 `SubscriptionSessionManager`를 통해 WebSocket 클라이언트에게 보정 메시지를 전송합니다.
    *   단일 WebSocket Endpoint (`/ws/v1/coinflow`)에서 Tick과 Event를 모두 처리하도록 개선했습니다.
    *   **[FEAT] Multi-Interval Support**: 
        *   `CandleClosedEvent`에 `interval` 필드를 추가 ("M1", "M5", "M30").
        *   `OhlcRollupExecutor` 수정: 5분/30분 롤업(Rollup) 완료 시에도 보정 이벤트를 발행하도록 개선했습니다.
*   **[FEAT] Volume Scaling Strategy**:
    *   **Precision & Performance**: 부동소수점 오차 방지와 고빈도 연산 최적화를 위해 내부적으로 볼륨을 **Long (Fixed-Point 10^8)** 형태로 저장.
    *   **VolumeScaler**: `coinflow-core`로 공통화하여, API/WebSocket 응답 시에는 클라이언트가 바로 사용할 수 있도록 **BigDecimal(Unscaled)**로 변환하여 제공.

### Frontend (CoinFlow-Web)
*   **[FEAT] WebSocket Type Handling**:
    *   `websocket.ts`: `TickDto`와 `CandleClosedEvent` 타입을 정의하고 Union Type (`WsMessage`) 처리 로직을 추가했습니다.
    *   `useCoinflowWebSocket`: 메시지 수신 시 JSON 파싱 및 타입 안전성을 확보했습니다.
    *   **[FIX] Connection Race Condition**:
        *   `WebSocketContext`: 연결 전발생한 `SUBSCRIBE` 요청이 유실되지 않도록 **Message Queue**를 구현하여, 연결 즉시(`onopen`) 자동으로 플러시(Flush) 되도록 개선했습니다.
*   **[FEAT] TradingChart Integration**:
    *   **Optimistic Update**: `TickDto` 수신 즉시 차트의 현재 캔들(OHLC)과 볼륨을 업데이트하여 지연 없는 사용자 경험을 제공합니다.
    *   **Server Correction**: `CandleClosedEvent` 수신 시 해당 분(Bucket)의 데이터를 서버 데이터로 강제 교체하여 데이터 불일치를 해소합니다.
    *   **Multi-Interval Filtering**: 수신된 보정 이벤트의 `interval`이 현재 차트의 타임프레임과 일치할 때만 반영하도록 필터링 로직을 추가했습니다.
    *   `chartHelpers.ts`: `TickDto` 구조에 맞춰 데이터 집계 로직을 수정했습니다.
*   **[FIX] LiveTicker**:
    *   변경된 `WsMessage` 타입(Union)을 안전하게 처리하도록 가드 로직(`isTickDto` 등)을 적용했습니다.

## 📝 Note (Trade-off Analysis)

이번 구현에서 고민했던 주요 기술적 트레이드오프입니다.

| 결정 항목 (Decision) | 선택 (Chosen) | 대안 (Alternative) | 이유 (Reasoning) |
| :--- | :--- | :--- | :--- |
| **Protocol** | **WebSocket Multiplexing** | Multiple Sockets | **리소스 효율성**: 클라이언트가 틱 데이터와 이벤트 수신을 위해 소켓을 2개 맺는 것보다, 단일 소켓에서 메시지 타입으로 분기하는 것이 브라우저/서버 리소스 관점에서 효율적입니다. |
| **Frontend Sync** | **Optimistic + Correction** | Pessimistic / Polling | **UX와 정합성**: 틱 데이터는 즉시 반영하여 속도감을 주고(Optimistic), 마감 데이터로 보정(Correction)하여 데이터 신뢰성을 모두 확보하는 하이브리드 방식을 택했습니다. |
| **Data Structure** | **Java Record** | Class (Lombok) | **불변성 및 모던 자바**: DTO의 목적(단순 데이터 전달)에 부합하며, 불변성이 보장되고 코드가 간결한 Record를 사용했습니다. (JDK 14+ 장점 활용) |
| **Date Serialization** | **ISO-8601 String** | Array / Timestamp | **호환성**: 프론트엔드(`new Date()`)와의 호환성을 위해 `LocalDateTime`을 복잡한 배열 대신 표준 문자열(String)로 직렬화했습니다. |
| **Volume Storage** | **Long (Fixed-Point)** | BigDecimal | **성능 및 정밀도**: 고빈도 덧셈 연산 시 CPU 효율성이 높은 `long`을 사용하되, 10^8 스케일링으로 소수점 정밀도를 보장했습니다. (Display 시점 변환) |

## 📌 Related Issue
- Closes #53
