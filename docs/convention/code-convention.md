# Curricraft Backend Code Convention

이 문서는 Curricraft 프로젝트의 백엔드 개발 시 준수해야 할 코드 컨벤션을 정의합니다.
CodeRabbit AI 및 동료 리뷰어는 이 규칙을 기반으로 코드 리뷰를 진행합니다.

## 1. Naming Conventions (명명 규칙)

### 1.1 기본 원칙
- **명확성**: 축약어를 지양하고, 의도가 명확히 드러나는 이름을 사용한다. (e.g., `cnt` -> `count`, `idx` -> `index`)
- **언어**: 변수명, 메서드명은 영어로 작성하며 문법에 맞게 짓는다. (동사+명사 형태 등)

### 1.2 클래스 및 인터페이스
- **Class**: `UpperCamelCase` (e.g., `MemberService`)
- **Interface**: `UpperCamelCase`. 인터페이스 이름에 `I` 접두사를 붙이지 않는다. (e.g., `MemberRepository`)
- **Implementation**: 구현체는 뒤에 `Impl`을 붙이거나, 구체적인 이름을 사용한다. (e.g., `MemberRepositoryImpl` or `JdbcMemberRepository`)

### 1.3 메서드 및 변수
- **Method**: `lowerCamelCase` (e.g., `findMemberById`)
- **Variable**: `lowerCamelCase` (e.g., `memberCount`)
- **Constant**: `UPPER_SNAKE_CASE` (e.g., `MAX_RETRY_COUNT`)

### 1.4 DTO & Entity
- **Entity**: 데이터베이스 테이블과 매핑되는 클래스. 접미사를 붙이지 않는다. (e.g., `Member`)
- **DTO**: 데이터 전송 객체. `Request`, `Response` 접미사를 사용하여 용도를 구분한다. (e.g., `MemberCreateRequest`, `MemberResponse`)

---

## 2. Architecture & Layering (아키텍처)

### 2.1 계층 구조
- **Controller**: 요청 검증(Validation), 서비스 호출, 응답 반환만 담당한다. 비즈니스 로직을 포함하지 않는다.
- **Service**: 트랜잭션 관리(@Transactional), 도메인 로직 실행을 담당한다.
- **Repository**: DB 접근 로직만 담당한다.
- **Domain**: 핵심 비즈니스 로직은 가능한 도메인 엔티티 내부 메서드로 응집시킨다.

### 2.2 의존성 주입 (DI)
- **생성자 주입(Constructor Injection)**을 필수 원칙으로 한다.
- `@Autowired` 필드 주입은 지양한다.
- Lombok의 `@RequiredArgsConstructor` 사용을 권장한다.

---

## 3. Coding Style & Clean Code (코딩 스타일)

### 3.1 Lombok 사용
- **@Data 지양**: 무분별한 Setter 사용과 `equals`, `hashCode` 문제를 방지하기 위해 `@Data` 대신 필요한 어노테이션만 조합하여 사용한다. (`@Getter`, `@ToString` 등)
- **@Setter 사용 제한**: Entity에는 Setter를 절대 사용하지 않는다. 상태 변경이 필요하면 명확한 의도를 가진 메서드를 만든다. (e.g., `changePassword()`)
- **@Builder**: 생성자 파라미터가 많을 경우 Builder 패턴을 사용한다.

### 3.2 불변성 (Immutability)
- 모든 필드는 가능한 `private final`로 선언하여 불변성을 유지한다.
- Java 14+ `record` 타입을 DTO에 적극 활용하는 것을 권장한다.

### 3.3 예외 처리 (Exception Handling)
- `try-catch`로 예외를 먹어버리는 행위를 금지한다.
- 비즈니스 예외는 `RuntimeException`을 상속받은 커스텀 예외(Custom Exception)를 던진다.
- 예외는 `GlobalExceptionHandler`(@RestControllerAdvice)에서 일관성 있게 처리한다.

### 3.4 메서드 설계
- **Early Return**: `else` 사용을 지양하고, 조건이 맞지 않으면 바로 return 하여 들여쓰기 깊이(Depth)를 줄인다.
- **1 메서드 1 책임**: 하나의 메서드는 하나의 기능만 수행하도록 작게 쪼갠다.

---

## 4. Testing (테스트)

- **JUnit 5 + AssertJ** 사용을 표준으로 한다.
- **테스트 명**: `@DisplayName`을 사용하여 한글로 명확한 테스트 의도를 서술한다. (e.g., `@DisplayName("회원가입 성공 시 DB에 저장된다")`)
- **구조**: `given` (준비) - `when` (실행) - `then` (검증) 패턴을 준수한다.
