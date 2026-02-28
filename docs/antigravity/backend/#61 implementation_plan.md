# [FEAT] Zero Data Loss OHLC Synchronization Architecture (#61)

## 📌 Feature Description
현재 다중 모듈(MSA) 아키텍처 환경에서, 클라이언트가 최초 접속하거나 타임프레임을 변경할 때 진행 중인 최신 1분봉(Live Candle)이 누락되는 "초기 동기화 간극(Initial Sync Gap)" 문제가 발생합니다.

이를 해결하기 위해 **Eventual Consistency(최종적 일관성)**와 **Zero Data Loss(무손실) Stream Replay** 아키텍처를 도입합니다.
본 계획은 이 아키텍처를 `consumer-app`, `api-app`, 그리고 `frontend` 3개 계층에 걸쳐 단계별로 구현하는 상세 로직을 정의합니다.

---

## 📚 Implementation Tasks

### [Step 1] Consumer: Redis Snapshot Offloading 구현
**목표:** 1초마다 메모리(`OhlcAccumulator`)에 쌓인 실시간 캔들 상태를 Redis에 스냅샷으로 저장합니다.

*   [ ] **`coinflow-core` 모듈:**
    *   `OhlcLiveSnapshotRepository` (Interface) 정의.
    *   `OhlcLiveSnapshotRepositoryImpl` (RedisTemplate 활용) 구현.
    *   Redis Key: `ohlc:live:{symbolId}:{interval}` (예: `ohlc:live:1:M1`)
    *   Data: `Ohlc1m` (JSON 형태로 직렬화하여 저장)
*   [ ] **`coinflow-consumer-app` 모듈:**
    *   기존 `Ohlc1mFlushScheduler` 또는 신규 `Ohlc1mSnapshotScheduler` 생성.
    *   `@Scheduled(fixedDelay = 1000)`를 통해 `Ohlc1mAggregationStore`의 `keysSnapshot()`을 순회.
    *   아직 닫히지 않은(Open) 캔들의 `OhlcAccumulator` 데이터만 추출하여 `OhlcLiveSnapshotRepository.save()` 호출.

### [Step 2] API: Snapshot Read 및 Stream Replay 병합 구현
**목표:** API 요청 시 과거 DB 캔들과 Redis의 Live 스냅샷을 가져오고, 그사이 누락된 0.x초의 틱 데이터를 Stream에서 꺼내 캔들을 완벽하게 최신화합니다.

*   [ ] **`coinflow-core` 모듈:**
    *   `TickRawStreamRepository` 등 Redis Stream(`tick:raw`)에서 특정 시간대(`start` ~ `end`)의 틱만 조회하는 Range 쿼리 기능(`XRANGE`) 추가 구현.
*   [ ] **`coinflow-api-app` 모듈 (`OhlcChartService.java`):**
    *   `Optional<RealTimeOhlcProvider> realTimeOhlcProvider` 의존성을 제거하고 직접 흐름 제어.
    *   **Phase A (Read DB):** DB에서 과거 캔들(N개) 조회.
    *   **Phase B (Read Snapshot):** Redis에서 1단계에서 작성한 최신 Live 스냅샷(예: `12:00:30.0` 기준) 1개 조회.
    *   **Phase C (Stream Replay):** 현재 API 호출 시점(`12:00:30.5`)을 확인하고, `Stream`에서 `30.0 ~ 30.5` 사이의 틱 데이터 조회.
    *   **Phase D (Merge & Return):** 스냅샷 캔들(`Phase B`)에 틱 데이터(`Phase C`)를 순차 적용(Apply)하여 무결점 1분봉 생성 후, 결과 리스트 맨 마지막에 병합하여 반환.

### [Step 3] Frontend: 안정적인 수용 로직 구현 (필요 시)
**목표:** 백엔드가 제공하는 무결점 데이터를 자연스럽게 차트에 반영하고, API 이전의 웹소켓 틱이 중복 적용되는 것을 방지합니다.

*   [ ] **`TradingChart.tsx` 등 프론트엔드 모듈:**
    *   API 응답의 마지막 차트 캔들을 차트에 렌더링.
    *   웹소켓 수신 모듈(`useCoinflowWebSocket`)에서, API 호출 완료 시점(`T`) 이전의 timestamp를 가진 실시간 Tick은 방어적으로 폐기(Ignore)하는 필터 로직 추가 여부 검토 및 구현.

---

## 🧪 Verification Plan
*   **Step 1 검증:** Consumer 구동 후 `redis-cli`에서 `GET ohlc:live:1:M1` 명령어를 통해 데이터가 1초 단위로 갱신되는지 확인.
*   **Step 2 검증:** API(`/api/v1/ohlc...`)를 Postman 혹은 Swagger로 수동 호출하여, 응답 리스트의 맨 마지막 요소가 현재 시간 기준 최신 거래량/가격을 완벽히 포함하는지 확인.
*   **Step 3 검증:** `npm run dev` 구동 후 M1 ➔ M5 ➔ M1 전환 시 우측 끝 캔들이 비어있거나 초기화(0으로 추락)되는 현상 소멸 확인.
