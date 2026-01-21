# Redis Streams Consumer Integration

Integrated Redis Streams Consumer to receive real-time tick data from the Collector module and broadcast it to connected WebSocket clients.

## 구체적인 작업 내용

1.  **Redis Stream 설정 (`RedisStreamConfig`)**
    *   Collector가 데이터를 발행하는 `tick:raw` 스트림을 구독하도록 설정했습니다.
    *   **Consumer Group (`ws-gateway-group`)**을 정의하여 여러 Gateway 인스턴스가 실행되어도 메시지 처리 부하를 분산하거나(설정에 따라) 안정적으로 수신할 수 있게 했습니다.
    *   **자동 그룹 생성**: 앱 시작 시 Consumer Group이 존재하지 않으면 `NOGROUP` 오류 방지를 위해 자동으로 `xGroupCreate`를 수행하는 로직을 추가했습니다.

2.  **Consumer 구현 (`TickRawStreamConsumer`)**
    *   `StreamListener`를 구현하여 새로운 틱 데이터(`MapRecord`)가 들어올 때마다 트리거됩니다.
    *   수신된 데이터를 JSON으로 직렬화한 후 `log.info`로 수신 여부를 시각적으로 확인할 수 있게 조치했습니다.
    *   **Broadcasting**: `WebSocketSessionManager`를 통해 현재 연결된 모든 웹소켓 세션에게 실시간으로 데이터를 전송합니다.

3.  **검증**
    *   Collector와 Gateway를 동시에 실행하여 데이터 흐름(Collector -> Redis Stream -> Gateway -> WebSocket Client)이 정상 동작함을 확인했습니다.

## 📝 Note
*   현재는 모든 틱 데이터를 모든 클라이언트에게 뿌리는 **Broadcasting** 방식입니다.
*   추후 특정 코인(Symbol)만 구독하는 기능이 추가될 예정입니다.
