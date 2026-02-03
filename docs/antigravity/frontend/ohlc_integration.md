
# #47 [FEAT] ohlc api 연동

## 1. 개요
Frontend `TradingChart` 컴포넌트에서 Backend OHLC API를 연동하여 1분(1m), 5분(5m), 30분(30m) 캔들 데이터를 시각화한다.

---

## 2. 관련 Backend API 분석

### 2.1 API Endpoint
- **Method**: `GET`
- **Path**: `/api/v1/ohlc/{symbolId}`

### 2.2 Request Parameters
| Parameter | Type | Required | Default | Description |
|-----------|------|----------|---------|-------------|
| `symbolId` | Path | Yes | - | 조회할 심볼의 ID (예: BTCUSDT의 ID) |
| `interval` | Query | No | `M1` | 캔들 주기 (`M1`, `M5`, `M30`) |
| `candles` | Query | No | `120` | 조회할 캔들 개수 |

### 2.3 Response Structure
```typescript
interface OhlcChartResponse {
    symbolId: number;
    interval: 'M1' | 'M5' | 'M30';
    candles: OhlcCandleSnapshot[]; // Renamed from snapshots to match backend
}

interface OhlcCandleSnapshot {
    bucketTime: string; // ISO 8601 (e.g., "2024-01-01T12:00:00")
    openPrice: number;
    highPrice: number;
    lowPrice: number;
    closePrice: number;
    volume: number;
}
```

---

## 3. Frontend 작업 상세

### 3.1 Type 정의 (`src/types/chart.ts`)
- 백엔드 응답 스펙에 맞춘 인터페이스 정의
- `OhlcInterval` 타입 정의 (`'M1' | 'M5' | 'M30'` -> UI 표시용 라벨 매핑 필요)

### 3.2 API Service 구현 (`src/api/ohlcApi.ts`)
- `axios` 또는 `fetch`를 사용하여 데이터 요청 함수 구현
- `fetchOhlcData(symbolId: number, interval: string, candles: number)`

### 3.3 Chart Component 연동 (`src/components/Chart/TradingChart.tsx`)
- **초기 로딩**: 컴포넌트 마운트 시 API 호출하여 초기 데이터 세팅
- **Interval 변경**: 1m, 5m, 30m 버튼 클릭 시 API 재호출 및 차트 갱신
- **데이터 가공**: API 응답(`candles`)을 라이트웨이트 차트(Lightweight Charts) 포맷(`{ time, open, high, low, close }`)으로 변환
- **실시간 Tick 연동**: WebSocket으로 수신된 Tick 데이터를 현재 활성화된 Timeframe(`activeTimeframe`)에 맞춰 캔들에 반영 (`aggregateTickToCandle`)

---

## 4. Task 목록 (Checklist)

- [x] **Type Definition**
    - [x] `OhlcCandleSnapshot`, `OhlcChartResponse` 인터페이스 추가
- [x] **API Implementation**
    - [x] `src/api/ohlcApi.ts` 생성 및 `getOhlcData` 함수 구현
- [x] **UI Integration**
    - [x] 차트 상단에 Interval 선택 버튼 (1m, 5m, 30m) 추가
    - [x] 선택된 Interval에 따라 API 호출 로직 연결
    - [x] 받아온 데이터를 Chart 라이브러리에 주입 (`setData`)
- [x] **Test**
    - [x] 1m 데이터 로딩 확인
    - [x] 5m 데이터 로딩 확인
    - [x] 30m 데이터 로딩 확인

---

## 📌 Summary
- Frontend `TradingChart` 컴포넌트와 Backend OHLC API 간의 연동 작업을 완료하였습니다.
- 사용자는 1분(1m), 5분(5m), 30분(30m) 간격으로 차트 데이터를 조회할 수 있으며, 실시간 Tick 데이터가 차트에 즉시 반영됩니다.

## 📚 Changes
- **src/types/chart.ts**: API 응답 규격에 맞춘 타입 인터페이스 정의 (`OhlcChartResponse` 등).
- **src/api/ohlcApi.ts**: OHLC 데이터를 Fetch하는 API 함수 구현 (Vite Proxy 설정 적용).
- **src/utils/chartHelpers.ts**: `aggregateTickToCandle` 함수 개선 (활성화된 Timeframe에 따른 캔들 생성 로직 추가).
- **src/components/Chart/TradingChart.tsx**:
    - API 데이터 연동 및 Interval 선택 UI 구현.
    - WebSocket 실시간 데이터 반영 ("M1" 외 Timeframe 지원).
    - 초기 로딩 시 데이터 Fetching 및 에러 핸들링 추가.
- **vite.config.ts**: API 서버(8081)와 WebSocket 서버(8080) 포트 분리를 위한 Proxy 설정 추가.

## 📝 Note
- Backend API 응답 필드명이 `snapshots`가 아닌 `candles`로 되어 있어 이를 수정하여 해결했습니다.
- WebSocket 서버(8080)와 API 서버(8081)의 포트 충돌 문제를 해결하기 위해 프론트엔드 Proxy 설정을 분리했습니다.
- 실시간 데이터 수집 시 선택된 Interval(1m, 5m, 30m)에 맞춰 캔들 생성 주기가 동적으로 변경됩니다.

## 📌 Related Issue
- Closes #47
