# #51 [FEAT] Tick 데이터 처리 프론트/백 동기화 (FrontEnd)

## 1. 개요 (Overview)
서버로부터 수신한 Tick 데이터를 기반으로 클라이언트에서 실시간 차트를 구성(Aggregation)하고, 서버와의 데이터 동기화를 처리하는 전략을 정의한다.

## 2. 현재 아키텍처 (As-Is Status)

### 2.1 데이터 수신 및 렌더링
- **Current**: WebSocket으로 데이터를 받고 있을 것으로 추정되나, 구체적인 'Client-Side Aggregation' (Tick을 받아서 캔들을 키워나가는) 로직이 명시적으로 구현/검증되지 않음.
- **Rendering**: 서버에서 주는 완성된 캔들 배열을 그리는 방식(Passive)에 가까울 수 있음 -> 실시간성 부족.

---

## 3. 구현 목표 (To-Be Strategy)

### 3.1 Client-Side Aggregation (Optimistic UI)
> *"서버를 기다리지 않고 먼저 그린다."*
- **Ohlc Store**: 프론트엔드 메모리(Store)에 '현재 진행 중인 캔들' 상태를 유지.
- **Tick Handling**:
    - `Tick` 수신 시: `High/Low` 갱신, `Close` 갱신, `Volume` 누적.
    - 차트 라이브러리(Lightweight Charts 등)의 `update()` 메서드 즉시 호출.

### 3.2 Bucket Transition (Zero Latency)
- 12:00:59 -> 12:01:00이 되는 순간, 서버 데이터가 없어도 프론트엔드가 **새로운 캔들을 생성(Push)** 하고 그리기 시작한다.
- 끊김 없는 사용자 경험(UX) 제공.

### 3.3 Data Synchronization (Correction)
- **Correction Logic**:
    - 평소에는 자체 계산 값으로 그리다가, 서버로부터 `CandleClosed` 이벤트(확정 데이터)가 오면 내 로컬 데이터를 버리고 서버 데이터로 교체(Swap).
    - 이를 통해 네트워크 유실이나 로직 차이로 인한 오차를 주기적(1분마다)으로 자동 보정.

## 4. 작업 목록 (Frontend Tasks)
- [ ] **Setup Store**: 실시간 캔들 데이터를 관리할 Store (Zustand/Redux 등) 설계.
- [ ] **Aggregation Logic**: Tick 수신 시 OHLC를 계산하는 유틸리티 함수 구현.
- [ ] **Sync Handler**: `CandleClosed` 이벤트 수신 시 데이터 교체 처리.
