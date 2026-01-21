# WebSocket Gateway Module Separation

Separated the WebSocket Gateway into an independent module `coinflow-ws-gateway` to isolate real-time traffic handling using Spring WebFlux.

## 구체적인 작업 내용

1.  **모듈 생성 (`coinflow-ws-gateway`)**
    *   기존 API/Collector 모듈과 분리하여 실시간 시세 데이터 처리를 전담할 독립 모듈을 생성했습니다.
    *   **Spring WebFlux**를 도입하여 Netty 기반의 고성능 비동기 처리를 지원합니다.

2.  **프로젝트 설정 (`settings.gradle`)**
    *   `settings.gradle`에 `include 'coinflow-ws-gateway'`를 추가하여 Gradle 멀티 모듈 프로젝트의 일원으로 등록했습니다.

3.  **빌드 설정 (`build.gradle`)**
    *   `spring-boot-starter-webflux`: WebSocket 서버 구현을 위한 핵심 의존성 추가.
    *   `spring-boot-starter-data-redis`: Redis Stream (`tick:raw`) 연동을 위한 의존성 추가.
    *   `:coinflow-common`, `:coinflow-infra-redis`: 공통 DTO 및 인프라 모듈 의존성 추가.
    *   `jar { enabled = false }`, `bootJar { enabled = true }`: 실행 가능한 JAR 생성을 명시적으로 설정.

4.  **애플리케이션 진입점 (`WsGatewayApplication.java`)**
    *   `@SpringBootApplication`이 적용된 메인 클래스를 생성하여 독립 실행 가능하도록 구성했습니다.

5.  **기존 모듈 빌드 수정**
    *   `coinflow-common`, `coinflow-core` 등 라이브러리 역할을 하는 모듈의 `bootJar`를 비활성화하여, 전체 빌드(`clean build`) 시 발생하던 메인 클래스 누락 오류를 해결했습니다.
