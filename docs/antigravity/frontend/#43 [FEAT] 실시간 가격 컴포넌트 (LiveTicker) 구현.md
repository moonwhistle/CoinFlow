# #43 [FEAT] 실시간 가격 컴포넌트 (LiveTicker) 구현

## 📌 Summary
WebSocket을 통해 실시간으로 비트코인(BTC/USDT) 가격 데이터를 수신하고, 전문 트레이딩 인터페이스 스타일의 모던한 UI로 시각화하는 `LiveTicker` 컴포넌트를 구현했습니다.

## 📚 Changes

### Frontend
- **`src/components/LiveTicker.tsx`**
    - `useCoinflowWebSocket` 훅을 사용하여 WebSocket 데이터 연동
    - `btcusdt` 심볼 구독 및 구독 해제 기능 구현
    - 이전 가격과 비교하여 가격 상승(Green)/하락(Red) 색상 변경 로직 추가
    - 수량(Quantity), 체결 시간(Time), 연결 상태(Status) 표시 추가
    - `Intl.NumberFormat`을 사용한 통화 포맷팅 적용
    - **Performance Optimization**: 포맷팅 함수 및 `Intl.NumberFormat` 인스턴스를 컴포넌트 외부로 분리하여 불필요한 리렌더링 및 객체 생성 방지

- **`src/components/LiveTicker.css` (New)**
    - Dark Mode 기반의 전문 트레이딩 UI 디자인 적용
    - Glassmorphism 효과 및 그라디언트 상단 바 적용
    - 반응형 레이아웃 및 애니메이션 효과 (Pulse) 추가

- **`src/types/websocket.ts`**
    - `TickData` 인터페이스 업데이트: Redis Stream 데이터 구조에 맞춰 `quantity`, `eventTime` 필드 추가
    - Optional 필드 처리를 위한 Index Signature 수정

## 📝 Note
- 현재는 로컬 개발 환경(`ws://localhost:8080`)을 기준으로 설정되어 있습니다.
- 초기 구독 심볼은 `btcusdt`로 고정되어 있습니다.

## 📌 Related Issue
- Closes #43
