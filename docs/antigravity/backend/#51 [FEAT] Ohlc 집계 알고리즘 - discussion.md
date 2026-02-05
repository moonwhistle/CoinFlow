#51 [FEAT] Ohlc 집계 알고리즘 개선 - Solution Discussion

## 📌 Problem: Data Gap
사용자가 API를 호출했을 때, 아직 DB에 저장되지 않은 "현재 진행 중인 캔들(Open Candle)" 데이터가 누락됨.

---

## 💡 Solution Options Analysis

### Option 1: Memory Merge Strategy (Recommended)
> *"API 요청 시, DB의 과거 데이터와 메모리의 현재 데이터를 합쳐서 반환한다."*

- **동작 방식**:
    1.  API: `DB.findAll()` (과거 데이터)
    2.  API: `MemoryStore.get(currentBucket)` (현재 데이터)
    3.  API: `List.addAll()` -> Return
    4.  WebSocket: 실시간 갱신 계속 수행.
- **장점**:
    - **Zero DB Write Load**: 진행 중인 데이터를 매번 DB에 쓸 필요가 없어 DB 부하가 '0'에 수렴한다.
    - **Real-time Consistency**: 메모리에 있는 가장 최신의 상태를 즉시 반환하므로 오차가 없다.
- **단점**:
    - **Complexity**: API 조회 로직이 "DB + Memory" 두 곳을 봐야 하므로 복잡해진다.
    - **Server Crash**: 서버가 갑자기 죽으면 메모리에 있던 "현재 진행 중인 30초 분량"이 유실된다. (치명적일 수 있음)

### Option 2: Frequent DB Flush (Periodic Update)
> *"1초마다 무조건 DB에 갱신(UPSERT)한다."*

- **동작 방식**:
    1.  Scheduler: 1초마다 `currentBucket` 데이터를 DB에 `save()`.
    2.  API: 그냥 `DB.findAll()` 하면 끝.
- **장점**:
    - **Simplicity**: API 조회 로직이 단순하다 (DB만 보면 됨).
    - **Durability**: 서버가 죽어도 최대 1초 분량의 데이터만 유실된다.
- **단점**:
    - **Heavy Write Load**: 심볼이 100개면 매 초마다 100번의 Update 쿼리가 발생한다. (초당 100 TPS 기본 깔고 감)
    - **Optimization Fail**: 변경 사항이 없어도 계속 쿼리를 날릴 위험이 있다.

---

## 🚀 Final Recommendation: Hybrid (Memory Merge + Lazy Persist)
가장 좋은 방법은 **Option 1(Memory Merge)**를 베이스로 하되, 서버 다운 시 데이터 유실을 막기 위해 **Option 2의 주기적 백업**을 "가볍게" 섞는 것입니다.

1.  **API 조회**: **Memory Merge** 사용. (사용자에게는 항상 최신 데이터 제공, DB 부하 없음)
2.  **데이터 보존**: 
    - 기본적으로는 메모리에서만 관리하다가 버킷이 닫힐 때(Close) Flush.
    - 단, 안정성을 위해 **N초(예: 10초)마다 한 번씩만** 중간 데이터를 DB에 백업(Snapshot). 
    - 또는 서버 종료 훅(`@PreDestroy`)에서 메모리 데이터를 DB로 덤프.

**결론**: 
우리의 목표인 "Single Server High Performance"에는 **Option 1 (Memory Merge)**가 가장 적합합니다. DB I/O를 극한으로 줄이면서도 사용자 경험(끊김 없음)을 완벽하게 보장할 수 있기 때문입니다. 단, 서버 재시작 시 데이터 유실 방지를 위한 우아한 종료(Graceful Shutdown) 처리는 필수입니다.

---

## ❓ Q&A: Efficiency & Architecture Validation

**Q1. API 요청 시마다 findAll을 수행하면 너무 비효율적인 것 아닌가? (처음 접속 시에도?)**
- **Answer**: 처음 접속할 때도 **`findAll`(테이블 전체 조회)**은 절대 하지 않습니다.
    - 차트는 보통 화면에 꽉 찰 정도의 개수(예: 120개)만 필요로 합니다.
    - 그래서 **"최근 120개만 줘"**라고 요청하며, DB에서는 인덱스를 타고 뒤에서부터 120개만 쏙 뽑아옵니다. 수십 년치 데이터가 있어도 0.01초면 끝납니다.

**Q2. 프론트엔드가 페이지 진입 시 1회만 호출한다면, 계속 변하는 '현재 1분 봉'은 어떻게 처리하나?**
- **Answer**: 이게 바로 **WebSocket + Frontend Aggregation**의 핵심입니다.
    1.  **Initial Load**: API가 `[과거 완료된 봉들, ..., 현재 12:00:05까지의 봉]`을 줍니다. 화면에 그립니다.
    2.  **Real-time Update**: 
        - 12:00:06에 50,000달러 Tick 도착 -> 프론트엔드가 방금 받은 마지막 봉(12:00:00)의 `Close` 값을 50,000으로 갱신합니다. `High/Low/Volume`도 갱신합니다.
        - 12:00:07에 50,100달러 Tick 도착 -> 또 갱신합니다.
    - 즉, **서버가 매번 완성된 봉을 주는 게 아니라, 프론트엔드가 Tick을 받아서 스스로 봉을 키워나가는 것**입니다. 그래서 API 재호출이 필요 없습니다.

**Q3. WebSocket은 '현재 분(Current Minute)'에 대해서만 열려 있나?**
- **Answer**: 아닙니다. WebSocket은 **모든 실시간 체결(Tick) 데이터**를 전송합니다.
    - 서버: 모든 Tick을 Broadcast.
    - 클라이언트: 수신된 Tick을 보고 "현재 열려있는 캔들"을 찾아 합침(Aggregation).
    - 이 구조 덕분에 서버는 클라이언트의 상태(어떤 캔들을 보고 있는지)를 알 필요가 없어(Stateless), 확장성이 매우 높습니다.

**Q4. 주식 차트가 원래 프론트에서 Tick을 쌓고, 새로운 분(New Minute) 전환도 처리하는가?**
- **Answer**: 네, 그것이 **업계 표준(Standard)**입니다. (TradingView, Binance 등도 동일)
    - **Frontend Aggregation**: 사용자는 0.1초의 딜레이도 민감하게 느낍니다. 서버가 DB에 넣고 다시 줄 때까지 기다리면 늦습니다. 받은 Tick으로 즉시 그리는 것이 맞습니다.
    - **Bucket Transition**: 12:00:59에서 12:01:00이 되는 순간, 프론트엔드가 Tick의 시간을 보고 **"어? 시간이 지났네?"**라고 판단하여 스스로 새로운 캔들을 생성(Push)합니다.
    - 즉, 프론트엔드는 **서버의 확인을 기다리지 않고 선제적으로(Optimistic)** 화면을 갱신합니다.


**Q5. 늦게 들어오는 데이터(Late Arrival)에 대한 싱크(Sync)는 어떻게 맞추는가?**
- **Answer**: **Event Time**이 기준이 됩니다.
    - **Timestamp 기반 처리**: 프론트/백엔드 모두 수신 시간이 아니라 `Tick.eventTime`(체결 발생 시간)을 보고 어떤 캔들에 넣을지 결정합니다.
    - **Late Tick**: 만약 12:00:59 체결 데이터가 네트워크 지연으로 12:01:02에 도착하더라도, 프론트엔드는 이를 보고 **지난 12:00분 캔들**을 찾아가서 값을 수정합니다.
    - **Convergence (자동 보정)**: 
        - 사용자의 새로고침(F5)에만 의존하는 것은 수동적입니다.
        - **Best Practice**: 캔들이 확정(Close)되는 시점(예: 12:01:00)에 서버가 **"완료된 1분 봉 정본"**을 Event로 Broadcast 합니다.
        - 프론트엔드는 자신이 임시로 그렸던 1분 봉을 버리고, 서버가 보내준 정본으로 교체(Swap)하여 모든 사용자의 차트 데이터 싱크를 맞춥니다.

**Q6. 차트 데이터가 프론트마다 다를 수 있는데, 그래도 괜찮은가? (Data Consistency vs Latency)**
- **Answer**: **"진행 중인 캔들(Forming Candle)"은 달라도 되지만, "완료된 캔들(Closed Candle)"은 같아야 합니다.**
    - **Tick Priority (체결 우선)**: 트레이딩에서 가장 중요한 데이터는 **"지금 얼마에 체결되는가(Current Price)"**입니다. 이는 매수/매도 의사결정의 핵심(Critical Core Data)이므로, 서버는 가공 없이 0.01초라도 빨리 쏴주는 것이 최우선입니다.
    - **Chart Visualization**: 차트는 추세를 보여주는 **시각화 도구(Derived View)**입니다. 현재가가 정확하다면 차트의 막대가 0.X초 늦게 그려지거나 미세한 오차가 있어도 트레이딩에 치명적이지 않습니다.
    - **결론**: 서버는 "Tick Broadcasting 속도"에 집중하고, 무거운 "Charting"은 각 클라이언트(Frontend)에 위임하여 서버 부하를 줄이고 반응 속도를 극대화하는 것이 정답입니다.


---

## 📅 Implementation Task Breakdown

### Phase 1: Core Data Structure (Memory Store)
- [ ] **Define OhlcMemoryStore Interface**: Define methods for addTick, getCurrentCandle, flush.
- [ ] **Implement In-Memory Storage**: Use ConcurrentHashMap to store valid CandidateOhlc per symbol.
- [ ] **Tick Aggregation Logic**: Implement logic to update High/Low/Close/Volume in memory when a new tick arrives.

### Phase 2: Persistence & Reliability (Hybrid Approach)
- [ ] **Implement Scheduled Flusher**: Create a @Scheduled task running every 10s to UPSERT memory state to DB.
- [ ] **Implement Graceful Shutdown**: Add @PreDestroy hook to flush remaining data to DB on server stop.
- [ ] **DB Repository Optimization**: Ensure UPSERT (Merge) query is efficient.

### Phase 3: Read API (Memory Merge)
- [ ] **Refactor OhlcService.getCandles()**:
    - Fetch history from logical DB (Redis/RDB).
    - Fetch current forming candle from OhlcMemoryStore.
    - Combine list and return.

### Phase 4: Real-time Convergence (WebSocket)
- [ ] **Verify Tick Broadcasting**: Ensure raw ticks are sent via WS immediately.
- [ ] **Implement Candle Close Event**:
    - Detect when minute changes (00s).
    - Broadcast CandleClosed event with final values.
    - Clear/Reset memory bucket for next minute.
