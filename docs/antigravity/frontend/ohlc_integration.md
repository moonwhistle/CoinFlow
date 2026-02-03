
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
    snapshots: OhlcCandleSnapshot[];
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
- **데이터 가공**: API 응답(`snapshots`)을 라이트웨이트 차트(Lightweight Charts) 포맷(`{ time, open, high, low, close }`)으로 변환

---

## 4. Task 목록 (Checklist)

- [ ] **Type Definition**
    - [ ] `OhlcCandleSnapshot`, `OhlcChartResponse` 인터페이스 추가
- [ ] **API Implementation**
    - [ ] `src/api/ohlcApi.ts` 생성 및 `getOhlcData` 함수 구현
- [ ] **UI Integration**
    - [ ] 차트 상단에 Interval 선택 버튼 (1m, 5m, 30m) 추가
    - [ ] 선택된 Interval에 따라 API 호출 로직 연결
    - [ ] 받아온 데이터를 Chart 라이브러리에 주입 (`setData`)
- [ ] **Test**
    - [ ] 1m 데이터 로딩 확인
    - [ ] 5m 데이터 로딩 확인
    - [ ] 30m 데이터 로딩 확인
