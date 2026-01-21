# WebSocket Gateway Basic Structure Implementation

Implemented the fundamental architecture for the WebSocket Gateway using Spring WebFlux, enabling high-performance real-time connection responsiveness.

## 구체적인 작업 내용

1.  **WebSocket 설정 (`WebSocketConfig`)**
    *   `/ws/v1/coinflow` 엔드포인트를 `CoinflowWebSocketHandler`에 매핑했습니다.
    *   `SimpleUrlHandlerMapping`의 우선순위를 `-1`로 설정하여 다른 핸들러보다 먼저 요청을 가로채도록 구성했습니다.
    *   **Netty 기반**의 리액티브 런타임 위에서 동작하여 적은 스레드로 대량의 동시 연결을 처리합니다.

2.  **핸들러 구현 (`CoinflowWebSocketHandler`)**
    *   클라이언트의 연결(Connect) 및 해제(Disconnect) 이벤트를 감지하고 로그를 남깁니다.
    *   연결된 세션(`WebSocketSession`)을 매니저에게 위임하여 관리합니다.

3.  **세션 관리자 (`WebSocketSessionManager`)**
    *   `ConcurrentHashMap`을 사용하여 메모리 내에서 활성 세션을 관리합니다.
    *   추후 브로드캐스팅(Broadcasting)을 위한 기반을 마련했습니다.

## ⚖️ Why WebFlux & Netty? (Runtime Comparison)

| 런타임 모델 | 설명 | 장점 | 단점 | WebSocket 적합성 |
| :--- | :--- | :--- | :--- | :--- |
| **Spring WebFlux** (Netty) | **Event Loop (Non-blocking)**<br>소수의 스레드로 수만 개의 연결을 처리 (Context Switching 최소화) | • **높은 동시성**: 적은 리소스로 대량 연결 유지 가능<br>• Scalability 우수 | • 디버깅이 다소 어려움 (Stack trace가 복잡)<br>• Blocking 호출 금지 (실수로 Blocking 시 전체 멈춤) | **⭐️ 최상** (Long-lived Connection 유지 비용이 매우 저렴) |
| **Spring MVC** (Tomcat) | **Thread-per-Request (Blocking)**<br>연결 하나당 스레드 하나 할당 (또는 Pool 사용) | • 익숙한 프로그래밍 모델<br>• 디버깅 용이 | • **Context Switching 비용 큼**<br>• 동시 접속자가 많아지면 스레드 고갈(Thread Exhaustion) 위험 | **보통** (소규모 연결엔 문제 없으나, 대규모 채팅/시세판엔 비효율적) |

> **선택의 이유**: WebSocket은 연결이 끊기지 않고 계속 유지되는 **Long-lived Connection** 특성을 가집니다. Tomcat(MVC) 방식은 연결 유지를 위해 스레드를 점유해야 하므로 대규모 트래픽 시 리소스 낭비가 심합니다. 반면, **Netty(WebFlux)**는 소수의 이벤트 루프 스레드만으로 수만 개의 Idle 연결을 효율적으로 관리할 수 있어, 실시간 시세 Gateway에 가장 적합한 아키텍처입니다.

## ⚖️ Real-time Tech Trade-offs (프로토콜 비교)

| 방식 | 설명 | 장점 | 단점 | 적합 사례 |
| :--- | :--- | :--- | :--- | :--- |
| **Raw WebSocket** (채택) | 순수 WebSocket 프로토콜 사용 | • **오버헤드 최소화** (헤더 작음)<br>• **최고 성능** (Netty와 궁합 좋음)<br>• 양방향 통신 | • 메시지 규격(Sub/Pub 등) 직접 구현 필요<br>• 로드밸런싱/세션 관리 복잡 | **고빈도 시세 데이터** (업비트, 바이낸스 등), 실시간 게임 |
| **WebSocket + STOMP** | WS 위에 메시징 규격 얹음 | • 구독/발행 모델 표준화 (`@MessageMapping`)<br>• Spring Security 등 생태계 연동 용이 | • **무거운 프레임** (텍스트 기반 헤더)<br>• 단순 스트리밍엔 불필요한 기능 많음 | 채팅 앱, 알림 시스템, 복잡한 비즈니스 로직 |
| **SSE** (Server-Sent Events) | HTTP 연결 유지하며 단방향 전송 | • **구현 매우 쉬움** (단순 HTTP)<br>• 방화벽/프록시 친화적<br>• 자동 재연결 지원 | • **단방향** (클라이언트 → 서버 불가)<br>• 바이너리 데이터 전송 비효율적 | 뉴스 피드, 간단한 알림, 단방향 시세판 |
| **Redis Pub/Sub** | (통신 프로토콜 아님) 백엔드 간 메시지 버스 | • 서버 간 메시지 브로드캐스팅 용이<br>• 속도가 매우 빠름 | • **메시지 영속성 없음** (구독자 없으면 증발)<br>• 클라이언트와 직접 통신 불가 (Gateway 필요) | 서버 간 이벤트 전파 (WebSocket 분산 처리를 위한 백본) |

> **선택의 이유**: CoinFlow는 **초당 수천 건의 시세 데이터**를 지연 없이 전송해야 하므로, 불필요한 오버헤드가 없는 **Raw WebSocket** (On Spring WebFlux) 방식이 가장 적합합니다. 확장성을 위해 백엔드 내부에서는 **Redis Streams/PubSub**을 백본으로 사용할 예정입니다.
