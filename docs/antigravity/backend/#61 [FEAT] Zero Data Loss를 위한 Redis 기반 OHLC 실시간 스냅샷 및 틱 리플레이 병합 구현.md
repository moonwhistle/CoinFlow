# [FEAT] Zero Data Loss를 위한 Redis 기반 OHLC 실시간 스냅샷 및 틱 리플레이 병합 구현

## 📌 Summary
초기에는 Snapshot + Replay 구조를 통해 실시간 데이터를 프론트엔드에서 집계하려고 하였으나, 정합성과 클라이언트 부담 문제를 해결하기 위해 백엔드에서 집계하여 완성된 캔들(Kline)을 1초마다 내려주는 구조로 아키텍처를 전면 개편하였습니다.
이제 `consumer-app`이 실시간 틱 데이터를 바탕으로 OHLC 데이터를 집계 후 분리하여, Redis에 저장 및 Pub/Sub으로 브로드캐스팅합니다. 이를 통해 **Zero Data Loss** 정합성을 달성하며 프론트엔드의 구조를 완전히 단순화하였습니다.

## 📚 Changes
- `coinflow-consumer-app`: `TickProcessService`와 `KlineAggregator`를 도입하여 틱 수신 시 실시간으로 M1, M5, M30 캔들을 메모리상에서 집계. 
- `coinflow-consumer-app`: Ticker(0ms 지연)와 Kline(250ms/1초 스로틀링) 푸시 전략 분리 구현.
- `coinflow-infra-redis`: `LiveKlineRepositoryImpl` 추가 및 Redis 키 관리 전략(심볼-인터벌 당 1개의 키 덮어쓰기) 구현.
- `coinflow-ws-gateway`: Redis Pub/Sub을 구독하여 클라이언트로 브로드캐스팅하는 역할로 단순화. 직접적인 상태 유지나 집계 로직 제거.
- 클라이언트: 틱 누적 로직(`aggregateTickToCandle`) 제거 및 서버 제공 Kline에 100% 의존하도록 단순화.

---

## 1. 원래 구현하고자 했던 Snapshot+Replay 흐름 정리
- **흐름**: 
  - API 초기 로딩 시 `DB 과거 캔들 + 마지막 Redis 스냅샷 + 누락 틱(XRANGE Replay)`을 병합해 응답.
  - 실시간으로는 `ws-gateway`가 낱개의 틱(Raw Tick)을 클라이언트로 쏴줌.
  - 프론트엔드에서 이 틱들을 가져다가 현재 캔들을 직접 OHLCV 갱신 및 집계(`aggregateTickToCandle`).
  - 1분이 지나면 서버에서 확정된 캔들 데이터(`CandleClosedEvent`)를 보내 클라이언트 메모리 값을 보정(Overwrite).

## 2. Snapshot+Replay 분석 및 장단점
- **장점**: 틱 발생 즉시(<100ms) 차트가 갱신되므로 실시간성이 매우 높음. 서버(`ws-gateway`)는 단순히 틱을 중계만 하므로 부하가 적음. `lastStreamId`를 통해 정밀한 멱등성 보장이 가능하여 이론 상 데이터 유실이 없음.
- **단점**: 클라이언트 복잡도가 극도로 높음. WebSocket 연결이 불안정하거나 브라우저 탭 비활성화 시 틱이 유실되면 서버와 화면의 일치성이 바로 깨짐. 봉이 마감되는 시점에 과거 데이터를 보정하는 과정에서 시간 역행 문제 등 상태 관리(State management)가 매우 까다로움.

---

## 3. Kline 흐름 정리 (현재)
- **흐름**: 
  - `consumer-app`이 Redis Stream에서 틱을 소비하며 메모리에서 즉시 캔들(Kline)로 응집화(Aggregation) 수행.
  - 마감되지 않은 라이브 캔들은 일정 주기(예: 1초, 혹은 250ms)로 스냅샷을 만들어 Redis에 저장하고 Pub/Sub으로 브로드캐스팅.
  - 캔들이 마감(Closed)되면 즉시 `closed: true` 플래그와 함께 브로드캐스팅 후 메모리 리셋 및 DB 영속화 진행.
  - 클라이언트는 별도의 계산 없이 수신된 Kline 스냅샷을 `series.update()`로 그대로 차트에 밀어넣어 렌더링만 갱신.

## 4. Kline Stream 분석 및 장단점
- **장점**: 프론트엔드는 온전히 "뷰(View)의 렌더링"만 담당하게 되어 매우 가벼워짐. 네트워크 유실이 발생하더라도 다음 1초 뒤에 날아오는 완성된 캔들 스냅샷 하나로 완벽하게 자가 복구됨(자가 치유). 모든 유저가 서버와 100% 동일한 정합성을 가진 차트를 보게 됨.
- **단점**: 각 틱마다 갱신되지 않고 지정한 간격(1초)마다 업데이트되므로 초단타 매매 수준의 초지연(Ultra-Low Latency) 실시간성에는 약간 손해를 봄. 모든 심볼과 인터벌에 대한 메모리 상태(State)를 `consumer-app`이 유지해야 하므로 서버 리소스가 더 필요함.

## 5. Kline 구조로 옮겨간 이유
거래소 차트에서 가장 중요한 코어 밸류는 극강의 실시간성(ms 단위)보다 **사용자가 보는 화면과 서버 내 데이터 간의 완벽한 정합성 보장**입니다. 
클라이언트 측에 집계를 맡겼을 때 발생하는 틱 유실, 상태 꼬임, 타임프레임 전환 시의 복잡도 문제가 가져오는 운영 리스크가 훨씬 컸습니다. 1초의 지연을 감수하더라도 프론트엔드가 언제나 100% 신뢰할 수 있는 서버의 캔들 데이터를 그대로 반영하도록 하는 것이 시스템의 안정성 면에서 훨씬 우수한 디자인(Best Practice)이기 때문에 아키텍처를 전면 수정하였습니다.

---

## 6. Consumer에서 Tick 데이터와 OHLC 데이터의 집계 및 분리 전략
- **분리 및 집계 방식**:
  - `TickProcessService`가 `TickRawEvent`를 가로채어 즉시 두 갈래로 나눕니다.
  - **Tick 처리 (Ticker Event)**: 가격, 거래량 변화 자체를 화면 우측 체결창 등에 보여주기 위해 집계 없이 즉시 `TickerBroadcaster`를 통해 퍼블리싱.
  - **OHLC 처리 (Kline Aggregation)**: `KlineAggregator`가 틱의 가격과 수량을 받아 메모리(`KlineState`) 속 M1, M5, M30 상태에 In-memory 롤업을 수행. `open`이 없으면 새로 생성, `high`/`low` 비교 갱신, `close` 최신화, `volume` 누적 더하기 방식으로 자체 응집시킵니다.

## 7. Consumer의 Push 전략 (틱 vs OHLC)
- **Tick Push 전략**: Ticker 이벤트는 지연이나 스로틀링 없이 발생하는 족족 100% 실시간(0ms Delay)으로 Redis Pub/Sub으로 퍼블리싱. (체결창 등 실시간성 최우선)
- **OHLC(Kline) Push 전략**: 불필요한 네트워크 트래픽 낭비와 렌더링 오버헤드를 막기 위해, 마감되지 않은 Live Snapshot은 일정 주기(예: Throttling 250ms 또는 1초) 단위로 모아서 빈도를 조절해 브로드캐스팅. 반면 버킷이 넘어가 마감된(Closed) 캔들은 지연 없이 즉시 브로드캐스팅 + DB 영속화 진행.

## 8. Kline에서의 Redis 키 관리 전략
- **키 구조**: `kline:live:{symbol}:{interval}` (예: `kline:live:btcusdt:M1`)
- **전략**: 심볼과 인터벌 조합당 단 1개의 키만 유지하며, 매 주기마다 해당 키 내부의 JSON 값만 **덮어쓰기(Overwrite)**.
- 이로 인해 Redis 메모리 부하가 최소화되며, `api-app` 등이 현재 가장 최신의 작성 중인 봉 정보가 필요할 때 수시로 `GET`하여 병합할 수 있는 SSOT(Single Source of Truth) 캐시 역할을 안정적으로 수행합니다.

## 9. WS-Gateway 모듈의 역할과 상호작용
- **역할 및 전략**: `ws-gateway`는 철저히 **Stateless 한 중계소(Router/Broadcaster)** 역할만 수행합니다. 
- 복잡한 캔들 생성이나 데이터베이스 조회를 하지 않고, 단순히 `consumer-app`이 정제해서 쏴주는 완제품(Kline JSON)을 Redis Pub/Sub(`kline:broadcast` 채널)에서 인계받습니다.
- 접속해 있는 여러 클라이언트(WebSocket 세션)들 중 해당 심볼을 구독 중인 연결들에게 Routing 및 1:N 릴레이 브로드캐스트만 담당합니다. 이로 인해 스케일 아웃이 극도로 용이해졌습니다.

---

## 📝 Note
### 프론트엔드 - 백엔드 책임 분리와 자율성
과거 구조에서는 프론트가 틱을 받아 캔들 수학을 직접 해야 하는 "무거운" 뷰였다면, 이번 개편을 통해 백엔드는 오직 완벽하게 계산된 캔들을 밀어주고, 프론트는 수동적으로 차트를 그리는 데 집중할 수 있게 되었습니다. 이는 Binance 등 글로벌 거래소의 WebSocket Stream 표준 규격과도 정확히 일치하여, 시스템의 안정성 및 통신 효율성을 크게 끌어올렸습니다.
