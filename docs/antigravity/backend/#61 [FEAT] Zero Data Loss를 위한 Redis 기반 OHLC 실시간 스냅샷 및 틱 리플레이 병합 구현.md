# [FEAT] Zero Data Loss를 위한 Redis 기반 OHLC 실시간 스냅샷 및 틱 리플레이 병합 구현

## 📌 Summary
`consumer-app`이 1초마다 메모리의 라이브 캔들을 Redis에 스냅샷으로 저장하고,
`api-app`이 이를 읽어 Redis Stream의 누락 틱까지 정밀 리플레이하여 **M1/M5/M30 모든 인터벌에서 Volume 이중 누적 없이** 최신 캔들을 반환합니다.

## 📚 Changes
- `coinflow-core`: `OhlcLiveSnapshotRepository` 인터페이스, `LiveCandleSnapshot` DTO, `Ohlc1mService` SRP 분리 (DB 전용)
- `coinflow-infra-redis`: `OhlcLiveSnapshotRepositoryImpl`, `OhlcChartSyncProvider` (`RealTimeOhlcProvider` 구현체)
- `coinflow-consumer-app`: `OhlcAccumulator`에 `lastStreamId` 추적, `TickRawEvent`에 `streamId` 전파, `Ohlc1mSnapshotScheduler`
- `coinflow-api-app`: `OhlcChartService`에서 M1/M5/M30 실시간 병합

---

## 전체 데이터 흐름

```
[Binance WebSocket]
       │ 틱 수신
       ▼
[collector-app] ── XADD ──→ [tick:raw Stream] ── StreamID 자동채번 ──→ "1772079194006-0"
                                   │
                                   ▼
                            [consumer-app]
                            ┌──── OhlcAccumulator ────┐
                            │  OHLCV + lastStreamId    │
                            └──────────┬───────────────┘
                                매 1초 │ fixedDelay=1000
                                       ▼
                              Redis ohlc:live:1:M1
                              {OHLCV, lastStreamId, bucketTime}
                              TTL = 10분
                                       │
                                       ▼
                               [api-app 요청 시]
                               ┌───────────────────────┐
                               │ 1) DB에서 과거 캔들 조회 │
                               │ 2) Redis 스냅샷 조회    │
                               │ 3) XRANGE Replay       │
                               │ 4) 병합 후 응답         │
                               └───────────────────────┘
```

---

## 각 컴포넌트 역할과 전략

### 1. Redis 키 전략

```
키:   ohlc:live:{symbolId}:{interval}
예시: ohlc:live:1:M1
```

- **심볼당 키 1개**: `bucketTime`은 Value 내부 JSON 필드로 관리
- **덮어쓰기 전략**: 매초 같은 키에 SET → 항상 최신 상태 유지
- **TTL 10분**: 거래량 가뭄, Consumer 장애 대비 안전망 (재시작 시 자연스럽게 갱신 재개)

### 2. Stream ID 생성 전략

```
형식: {밀리초 타임스탬프}-{시퀀스 번호}
예시: 1772079194006-0
```

- `XADD tick:raw *` → Redis 서버가 자동 생성
- **단조 증가 보장**: 시간 역행 시에도 마지막 ID보다 큰 값 강제 생성
- **밀리초 내 순서**: 같은 밀리초에 복수 건이면 `-0`, `-1`, `-2`로 자동 증가

### 3. `lastStreamId` — 체크포인트(Checkpoint)

멱등성 키가 아닌 **읽기 위치 마커(Cursor)**입니다. Kafka Consumer Offset, DB WAL LSN과 동일한 개념으로, **같은 데이터를 두 번 처리하지 않도록 경계를 설정**합니다.

```json
{
  "symbolId": 1,
  "symbolCode": "btcusdt",
  "bucketTime": "2026-02-26T04:13:00",
  "open": 68570.22, "high": 68613.69, "low": 68565.96, "close": 68568.25,
  "volume": 931941000,
  "lastStreamId": "1772079194006-0"  ← 이 틱까지 반영됨
}
```

API가 `XRANGE tick:raw (1772079194006-0 +` 로 조회하면 **이후 틱만** 반환됩니다. `(` prefix = exclusive start.

### 4. 분 전환 메커니즘

같은 키에 새 버킷 데이터가 덮어쓰기 됩니다:

```
12:00:59  스냅샷 저장 → {bucketTime:"12:00", vol:5200}
12:01:00  ── 분 전환 ── (12:00 Close → DB Flush, 12:01 Open)
12:01:01  스냅샷 저장 → {bucketTime:"12:01", vol:100}   ← 같은 키 덮어쓰기
```

**`bucketTime` 가드로 과거 스냅샷 오사용 방지:**

```java
if (!snapshot.bucketTime().equals(bucketTime)) {
    return Optional.empty();  // 시간 불일치 → 무시
}
```

전환 직후 Redis에 이전 분 데이터가 남아있어도, API는 요청한 시간과 일치하지 않으면 무시합니다.

### 5. 인터벌별 병합 전략 (M1 / M5 / M30)

| 인터벌 | DB 데이터 | 실시간 데이터 | 병합 방식 |
|---|---|---|---|
| **M1** | `Ohlc1mService` → DB 조회 | Redis 스냅샷 + XRANGE Replay | M1 캔들을 **교체 또는 추가** |
| **M5** | `Ohlc5mService` → DB 롤업 | M1 라이브 캔들 1개 | 해당 M5 버킷에 OHLCV **합산** |
| **M30** | `Ohlc30mService` → DB 롤업 | M1 라이브 캔들 1개 | 해당 M30 버킷에 OHLCV **합산** |

**M5/M30 합산 예시** — 12:03에 M5 차트 조회 시:

```
M5 버킷 {12:00~12:05}
  = DB 롤업 (12:00 + 12:01 + 12:02의 M1 합산)
  + Redis M1 라이브 (12:03, 현재 진행 중)
  ─────────────────────────────────────
  Open   = DB 유지 (12:00의 시가)
  High   = max(DB high, 라이브 high)
  Low    = min(DB low,  라이브 low)
  Close  = 라이브 close (최신)
  Volume = DB volume + 라이브 volume
```

M5/M30은 별도의 Redis 스냅샷을 저장하지 않습니다. **M1 스냅샷 1개를 재사용**하여 해당 부모 버킷에 합산합니다.

---

## Volume 이중 누적이 발생하지 않는 이유

| 기존 (bucketTime 기반) | 개선 (lastStreamId 기반) |
|---|---|
| `XRANGE tick:raw {12:00:00}-0 +` | `XRANGE tick:raw ({lastStreamId} +` |
| Stream의 **모든 틱** 재합산 → 호출마다 Volume 증가 ❌ | **미반영 틱만** 합산 → Volume 일관 ✅ |

---

## 장애 시나리오별 동작

| 상황 | Replay 범위 | 결과 |
|---|---|---|
| 정상 (1초 갱신) | 0~1초치 틱 (수십 건) | 정상 |
| Consumer 3초 지연 | 3초치 틱 | XRANGE로 복구 |
| Consumer 5분 다운 | 5분치 틱 | TTL 10분 이내, 정상 동작 |
| Consumer 15분 다운 | — | TTL 만료, DB 데이터만 반환 |

## 📝 Note
### Redis 스냅샷 TTL을 1분(60초)이 아닌 10분으로 설정한 이유
라이브 캔들(1분봉)이므로 직관적으로 1분 TTL이 맞아 보이지만, 실제 트레이딩 시스템 환경에서는 다음과 같은 2가지 치명적인 이슈를 방어하기 위해 여유 있는 TTL(10분)을 부여합니다.

1. **거래량 가뭄(Low Liquidity) 방어 로직**
   만약 새벽 시간에 비트코인 거래가 1분 30초 동안 단 한 건도 발생하지 않았다고 가정해 봅시다.
   - **TTL이 1분일 경우:** 1분이 지나면 Redis에서 이 라이브 캔들 키가 증발(Expired)해버립니다. 누군가 이때 접속해서 API를 호출하면, 1초 전까지 유지되던 캔들 모양이 통째로 날아가고 클라이언트 화면에서 캔들이 아예 사라지는(또는 0으로 나타나는) 치명적인 버그가 발생합니다.
   - **TTL이 10분일 경우:** 거래가 없어서 Redis 키가 갱신(`SET`)되지 않더라도, API는 10분 동안 "마지막으로 거래됐던 캔들의 완성본"을 안전하게 읽어갈 수 있습니다.

2. **Consumer 장애 전파 지연 (Fault Tolerance)**
   만약 `consumer-app` 프로세스가 OOM으로 죽거나 배포 때문에 30초 정도 잠시 다운되었다고 가정해 봅시다.
   - **TTL이 1분일 경우:** 짧은 순단에도 스냅샷이 바로 날아갈 확률이 높아, 의존하고 있던 `api-app`의 조회 결과까지 깨지게 됩니다.
   - **TTL이 10분일 경우:** 컨슈머가 죽더라도 프론트엔드는 이전 스냅샷 상태를 기반으로 차트를 그대로 유지할 수 있으며, 컨슈머가 빠르게 재시작되면 자연스럽게 다시 덮어쓰기가 시작되므로 사용자들은 서버 장애를 거의 느끼지 못하게 됩니다.

결승선에 도달한(Closed) 캔들은 알아서 Flushing 스케줄러가 DB로 넣기 때문에, 10분이라는 긴 TTL은 단순히 **"만약의 사태를 대비한 안전망(Safety Net)"** 역할일 뿐, 메모리나 로직에 나쁜 영향을 주지 않습니다.

## 📌 Related Issue
Closes #61

---

# Snapshot+Replay vs Kline Stream — 아키텍처 비교

## 1. 기존 방식: Snapshot + Replay + Client-Side Tick 합산

### 전체 흐름

```
[1] 초기 데이터 로딩 (HTTP API)
──────────────────────────────────────
Client → GET /api/ohlc?symbol=1&interval=M1&count=120
                │
                ▼
         [api-app] 3단계 병합:
         ┌──────────────────────────────────────────┐
         │ ① DB 조회: 과거 N개 봉 (M1, M5, M30)     │
         │ ② Redis 스냅샷 조회: ohlc:live:1:M1      │
         │    → {OHLCV, lastStreamId, bucketTime}   │
         │ ③ XRANGE Replay: tick:raw 에서            │
         │    lastStreamId 이후 미반영 틱 합산        │
         │ ④ 병합: DB + 스냅샷 + Replay → 응답       │
         └──────────────────────────────────────────┘
                │
                ▼
         Client: series.setData(candles)

[2] 실시간 갱신 (WebSocket)
──────────────────────────────────────
[tick:raw Stream] → [ws-gateway] → Raw Tick JSON → [Client]
                                                      │
                                                      ▼
                                              aggregateTickToCandle()
                                              ┌─────────────────────┐
                                              │ 같은 분? → OHLCV 갱신│
                                              │ 새 분?  → 새 캔들 생성│
                                              │ volume += tick.qty  │
                                              └─────────────────────┘
                                                      │
                                                      ▼
                                              series.update(candle)

[3] 서버 보정 (WebSocket)
──────────────────────────────────────
[consumer-app] → Redis Pub/Sub "candle:closed" → [ws-gateway] → [Client]
                                                                    │
                                                                    ▼
                                                        CandleClosedEvent 수신
                                                        series.update(correctedCandle)
```

### 핵심 메커니즘

| 단계 | 역할 | 데이터 |
|------|------|--------|
| **① DB 조회** | 과거 확정 봉 | M1/M5/M30 테이블 |
| **② Redis 스냅샷** | 현재 진행 중인 봉 (1초 주기 갱신) | OHLCV + `lastStreamId` |
| **③ XRANGE Replay** | 스냅샷 이후 누락 틱 보정 | `(lastStreamId` ~ `+` |
| **④ 클라이언트 틱 합산** | 실시간 OHLCV 누적 | `aggregateTickToCandle()` |
| **⑤ CandleClosed 보정** | 1분 경과 후 서버 확정값 오버라이트 | `CandleClosedEvent` |

---

## 2. 기존 방식의 장단점

### 장점

| 항목 | 설명 |
|------|------|
| **Zero Data Loss** | `lastStreamId` 기반 XRANGE로 스냅샷 ~ API 호출 사이의 모든 틱을 재현 |
| **틱 단위 실시간성** | 개별 틱 전달 → 100ms 이내 차트 갱신 가능 |
| **서버 부하 최소** | ws-gateway는 틱을 단순 중계만 하므로 집계 로직 없음 |
| **멱등성 보장** | `lastStreamId` 체크포인트 → Volume 이중 누적 없음 |

### 단점

| 항목 | 설명 |
|------|------|
| **클라이언트 복잡성** | 프론트엔드가 `aggregateTickToCandle()` 로 OHLCV 직접 집계 → 버그 발생 위치 다양 |
| **데이터 정합성 불안정** | WebSocket 끊김, 브라우저 탭 비활성화 시 틱 유실 → 서버와 OHLCV 불일치 |
| **CandleClosed 시간 회귀** | 다음 분의 틱이 먼저 도착하면 `series.update()` 시간 회귀 제한으로 보정 실패 |
| **타임프레임 전환 버그** | `currentCandleRef` 리셋 누락, `isLoading` 경합 등 상태 관리 복잡 |
| **프론트-백엔드 이중 집계** | 같은 OHLCV 로직을 백엔드(OhlcAccumulator)와 프론트(`aggregateTickToCandle`) 양쪽에서 구현 |

---

## 3. Kline Stream 방식 (Binance 표준)

### 동작 방식

```
[tick:raw Stream] → [ws-gateway KlineAggregator]
                          │
               ┌──────────┼──────────┐
               ▼          ▼          ▼
           KlineState  KlineState  KlineState
           (M1)        (M5)        (M30)
               │          │          │
               └──────────┼──────────┘
                          │ @Scheduled(fixedRate = 1000ms)
                          ▼
                 KlineSnapshotBroadcaster
                          │
                          ▼ WebSocket
              ┌───────────────────────┐
              │ {                     │
              │   "symbol": "btcusdt",│
              │   "interval": "M1",  │
              │   "startTime": 17400,│
              │   "open": 89000,     │
              │   "high": 89100,     │
              │   "low": 88900,      │
              │   "close": 89050,    │
              │   "volume": 23.92,   │
              │   "closed": false    │← 봉 진행 중
              │ }                     │
              └───────────────────────┘
                          │
                          ▼
              Client: series.update(kline)
              (서버 값을 그대로 렌더링)
```

### CandleClosed 통합

기존의 별도 `CandleClosedEvent` 대신, **같은 Kline 메시지에 `closed: true` 플래그**로 봉 마감을 알립니다:

```json
{ "interval": "M1", "closed": true, "open": 89000, "close": 89050, ... }
```

`closed: true` 수신 후 → aggregator 리셋 → 다음 틱부터 새 캔들 시작.

### Kline이 해결한 기존 방식의 단점

| 기존 단점 | Kline 해결 방식 |
|-----------|----------------|
| **클라이언트 OHLCV 집계 복잡성** | 서버가 집계, 클라이언트는 `series.update()`만 호출 |
| **틱 유실 시 데이터 불일치** | 서버가 모든 틱을 반영한 OHLCV를 매초 전송 → 1초 이내 자동 복구 |
| **CandleClosed 시간 회귀** | `closed` 플래그가 같은 Kline 메시지에 포함 → 별도 이벤트 불필요 |
| **이중 집계 코드** | 프론트엔드의 `aggregateTickToCandle()` 완전 제거 |
| **타임프레임 전환 상태 관리** | `currentCandleRef`/`currentVolumeRef` 제거 → 상태 관리 불필요 |

### Kline 장단점

| 항목 | 장점 | 단점 |
|------|------|------|
| **데이터 정합성** | 항상 서버와 100% 일치 | — |
| **프론트엔드 단순화** | 렌더링만 담당, 집계 로직 0 | — |
| **실시간성** | — | 최대 1초 지연 (틱 단위가 아닌 스냅샷 단위) |
| **서버 부하** | — | ws-gateway에 인메모리 집계 + 스케줄링 추가 |
| **메모리** | — | 심볼×인터벌별 KlineState 유지 |

---

## 4. 트레이드오프 비교

| 비교 항목 | Snapshot+Replay+Tick | Kline Stream |
|-----------|---------------------|--------------|
| **실시간 갱신 주기** | 틱 단위 (~100ms) | 1초 |
| **데이터 정합성** | 틱 유실 시 불일치 가능 | 항상 서버와 일치 |
| **프론트 코드 복잡도** | 높음 (집계 로직, 상태 관리) | 낮음 (렌더링만) |
| **서버 코드 복잡도** | 낮음 (단순 중계) | 중간 (KlineAggregator) |
| **네트워크 트래픽** | 높음 (틱 건건이 전송) | 낮음 (1초 1회 × 인터벌 수) |
| **봉 마감 처리** | 별도 CandleClosed 이벤트 → 시간 회귀 문제 | `closed: true` 플래그 → 동일 메시지 |
| **장애 복구 (API)** | Redis 스냅샷 + XRANGE Replay | 동일 (API 레이어 변경 없음) |
| **확장성** | 심볼 추가 시 ws-gateway 무상태 | 심볼당 메모리 증가 (미미) |

### 결론

**Kline Stream을 도입한 이유:**

> 거래소 차트에서 가장 중요한 것은 **"사용자가 보는 데이터 = 서버의 데이터"**라는 정합성이다.
> 틱 단위 실시간성(100ms vs 1초)의 트레이드오프는 캔들스틱 차트에서 체감 차이가 거의 없는 반면,
> 틱 유실로 인한 OHLCV 불일치는 사용자에게 직접적인 혼란을 준다.
> **1초 지연을 감수하고 100% 정합성을 얻는 것**이 트레이딩 차트의 올바른 선택이다.

기존 Snapshot+Replay 아키텍처는 **API 레이어(최초 데이터 로딩)**에서는 여전히 유효하며,
Kline Stream은 **WebSocket 레이어(실시간 갱신)**에만 적용됩니다. 두 방식은 공존합니다.
