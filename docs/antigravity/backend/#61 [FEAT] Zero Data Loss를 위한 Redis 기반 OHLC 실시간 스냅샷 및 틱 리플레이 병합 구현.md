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
