# Consumer 복구·DLQ·배치 정리

## 처리 원칙

- 단일 raw stream / consumer group / 활성 consumer 프로세스가 전제다. 기존 프로세스 종료를 확인한 뒤 교체하며, rolling overlap·수평 확장은 지원하지 않는다. DB 체크포인트 lease(30초)는 stale owner의 DB 커밋을 막는 fence이지, 여러 프로세스의 Redis 읽기 자체를 직렬화하는 장치는 아니다. 프로세스 강제 종료 후에는 lease 만료까지 재시작이 거절될 수 있다.
- 500건 또는 50ms 단위로 마감·지연 수정 봉, M1/M5/M30 메모리 상태와 최근 마감 버퍼, 중복 감지 상태, 마지막 RecordId를 **하나의 DB 트랜잭션**으로 저장한다. 커밋 전에는 정상 메시지를 ACK하지 않는다.
- DB 오류는 메시지 오류로 간주하지 않는다. 소비를 중단하고 체크포인트 이후를 재시작 복구한다. 부분 변경된 집계 결과를 다시 더하지 않는다.
- 재기동은 상태 복원 → 체크포인트 이하 정상 PEL 정리 → 고정한 group last-delivered-id까지 `XRANGE` 페이지 재생 → 커밋 → 신규 소비 순서다. ACK됐지만 메모리가 유실된 레코드도 복원해야 하므로 `XAUTOCLAIM`만으로 복구하지 않는다.
- `XACK`는 해당 group의 PEL만 제거한다. 원본 Stream 레코드를 삭제하지 않는다. ACK 응답 유실은 재시도 및 체크포인트 기반 PEL sweep으로 정리한다.

## 개별 메시지 실패

1. `failed_record`에 원본 stream/group/id, payload(Base64), 사유, 영향을 받는 봉 키를 먼저 저장한다. 이 DB 저장이 실패하면 소비를 중단한다.
   실패 기록이 커밋된 ID는 재집계하지 않을 결정으로 체크포인트에 반영한다. 이는 ACK 허가가 아니며 DLQ 실패 시 해당 PEL은 유지한다. 첫 메시지가 오류여도 재기동 위치를 잃지 않는다.
2. Redis Lua에서 DLQ 타입/group을 먼저 확인하고 `XADD → 중복 인덱스 기록 → 원본 XACK` 순서로 실행한다. 기본 DLQ는 `tick:raw:dlq`, 인덱스는 `tick:raw:dlq:index`다.
3. 총 3회, 기본 1초·2초 exponential backoff + jitter로 시도한다. 실패 시 `DLQ_PUBLISH_EXHAUSTED`를 기록하고 원본을 ACK하지 않는다. 시도 횟수는 DB에 기록해 재시작으로 무한 재시도하지 않는다.
4. DLQ 저장 성공은 봉 복구 완료와 다르다. 상태는 `WAITING_REPAIR → ACK_PENDING → RESOLVED`다. 관련 봉이 **전부 검증 완료**돼야 RESOLVED로 갈 수 있다. 어느 봉인지 알 수 없는 손상 payload는 자동 완료 처리하지 않는다.

DLQ는 조사 기록이며 자동 tick 재집계 워커를 두지 않는다. 후속 워커를 붙이더라도 이미 검증된 봉에 거래량을 다시 더해서는 안 된다.

Lua는 실행 중 다른 명령의 끼어들기를 막지만 실패한 앞선 명령을 롤백하지 않는다. XADD 후 인덱스 기록이 OOM 등으로 실패하면 원본은 ACK되지 않고 조사용 DLQ 중복 행이 생길 수 있다. `failureId`가 논리적 중복 식별자다. 전체 성공 후 응답만 유실된 경우에는 인덱스로 같은 DLQ ID를 반환한다. 현재 구성은 standalone Redis이며 Cluster 전환 시 세 키를 같은 hash slot에 배치해야 한다.

## 배치와 PEL 정리

- 실제 확인한 닫힌 1분봉만 저장 값과 `verified_candle`을 같은 트랜잭션으로 기록한다. 기존 값과 동일한 봉도 검증 표시를 남긴다.
- 5분/30분봉은 연속된 검증 완료 1분봉 5개/30개가 모두 있을 때만 확정한다. job COMPLETED 또는 단순 시간 상한만으로 PEL을 비우지 않는다.
- consumer 쓰기와 배치 chunk는 공통 DB 행 잠금으로 직렬화한다. 검증 완료 봉에는 consumer의 늦은 DB 쓰기·캐시 발행을 허용하지 않는다.
- 배치 후 캐시 갱신 실패는 `cache_published=false`로 남겨 재시도한다. Redis ACK 실패는 `ACK_PENDING`을 유지한다.
- consumer/replay에서 5초마다 최대 PEL 500건·실패 100건·캐시 100건씩 확인한다. 이전 시간대 검증 완료 건도 계속 정리한다. 최근 120분 밖의 실패 봉은 별도 backlog scheduler가 회당 30분 범위 하나를 보정한다.
- `RecoveryMaintenanceService`는 core의 정리 정책, `RecoveryStreamRepository`는 Redis 작업 포트다. 실제 명령은 infra-redis, 재시작 조율은 consumer, authoritative 검증은 replay가 담당한다.

## 보존·운영 설정

- 기본 producer `MAXLEN`을 0으로 두어 임의 개수 기준 삭제를 중단한다. maintenance가 `min(체크포인트, 가장 오래된 PEL)` **미만**만 `XTRIM MINID`로 정리하고 체크포인트 anchor 자체는 보존한다. 다른 group이 있으면 자동 trim하지 않는다.
- production Redis는 AOF `appendfsync always`, `noeviction`이다. consumer/replay 정지 또는 해결 불가 PEL 때문에 용량이 차면 Redis 쓰기가 실패한다. `used_memory`, 디스크, PEL, `failed_record`, DLQ 크기를 감시해야 한다. DLQ/index/실패·검증 이력의 무조건 MAXLEN/TTL 삭제는 하지 않는다. 운영 보존기간에 따라 RESOLVED 이력을 내보낸 후 보관·정리한다.
- 기존 collector DIRECT 모드는 Redis 장애 이전 입력의 내구성까지 보장하지 않는다. 필요하면 기존 `COLLECTOR_DELIVERY_MODE=WAL_PIPELINE`을 선택한다. 이 WAL은 process crash 복구용이며 fsync를 하지 않아 호스트 전원 장애까지 보장하지 않는다.
- DB/Redis 데이터·볼륨 자체 유실, 외부 XDEL/XTRIM, 데이터센터 장애까지 exactly-once를 보장하는 구조는 아니다. 백업/복제/디스크 오류 감시는 별도로 필요하다.
- source RecordId 기준 재생은 체크포인트로 중복 차단한다. producer가 같은 trade를 다른 RecordId로 다시 넣는 경우의 추가 dedupe는 최근 10분/최대 100,000개 범위다. 이 범위를 넘는 중복의 최종 봉 정합성은 Binance 배치가 보정한다.
- production compose에 replay-app을 추가했다. 기존 1GB 서버에 배치 JVM까지 올리므로 메모리 용량을 재확인해야 한다. DB checkpoint 직렬화·AOF always의 처리량 영향은 별도 부하 검증 대상이다.

## 최초 적용 / 재시작

기존 group에 전달 이력이 있는데 DB 체크포인트가 없으면 `RECOVERY_BASELINE_REQUIRED`로 시작을 거절한다. 마지막 ID만 임의로 넣거나 PEL 전체를 ACK해서 우회하지 않는다.

1. 기존 데이터 백업 후 collector/consumer를 중지한다. Hibernate `ddl-auto=update` 또는 `infra/sql/consumer-recovery.sql`로 복구 테이블을 준비한다.
2. 기존 raw 보존 범위와 배치 보정 범위를 확인한다. 현재 진행 중인 최대 30분봉을 재구성할 원본이 충분한지 확인한다. 이미 trim된 원본을 이 기능이 되살리지는 못한다.
3. 필요한 과거 구간을 배치로 보정한 뒤, 보존 원본으로 초기 상태를 만드는 것을 운영자가 승인한 경우에만 `COINFLOW_RECOVERY_ALLOW_INITIAL_REPLAY=true`로 한 번 시작한다. 일반 재시작에서는 false를 유지한다.
4. `RECOVERY_COMPLETED`, 체크포인트 증가, PEL 감소를 확인한다. anchor가 없으면 `RECOVERY_HISTORY_MISSING`으로 중단하므로 원본 복원/재기준 설정을 조사한다.

별도 DB outbox나 DLQ tick 재처리 서비스는 추가하지 않았다. 실패 추적 테이블은 배치 보정 및 ACK 재시도 근거다.

## 검증 실행

일반 단위/H2 테스트: `cd backend; .\gradlew.bat test`.

실제 Redis 통합 테스트는 전용 Redis의 포트를 `COINFLOW_TEST_REDIS_PORT`에 설정하면 실행된다. PostgreSQL 검증은 비어 있는 전용 DB `recovery_test`와 사용자/암호 `recovery_test`를 준비하고 `COINFLOW_TEST_DB_URL`도 설정한 뒤 `:coinflow-consumer-app:test --tests '*RecoveryIntegrationTest' --rerun-tasks`를 실행한다. 이 테스트는 create-drop 스키마를 사용하므로 **운영 DB/Redis에 연결하지 않는다**.

확인 항목: M1/M5/M30 상태/늦은 버퍼 복원, ACK된 원본까지 재생, 체크포인트-봉 저장 롤백, PEL 페이지 정리, 불완전 배치 범위 보류, ACK_PENDING 재시도, 검증된 봉 덮어쓰기 차단, DLQ 실패 3회 제한, Lua 타입 오류와 재호출 멱등성.
