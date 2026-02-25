# [FEAT] Zero Data Loss를 위한 Redis 기반 OHLC 실시간 스냅샷 구현

## 📌 Summary
API 모듈이 클라이언트 초기 동기화 시 누락되는 라이브 캔들을 보강할 수 있도록, `consumer-app`에서 1초마다 메모리의 최신 `Ohlc1m` 상태를 Redis에 스냅샷 형태로 오프로드(Offload)하는 배치 로직을 구현했습니다. (Step 1 완료)

## 📚 Changes
- `coinflow-core`: Redis에 Live 캔들 스냅샷을 저장하기 위한 `OhlcLiveSnapshotRepository` 인터페이스 추가
- `coinflow-consumer-app`: 
  - `StringRedisTemplate`을 이용해 Redis Hash/String 구조에 `ohlc:live:{symbolId}:{interval}` 키 형태로 스냅샷을 JSON 직렬화하여 덮어쓰는 `OhlcLiveSnapshotRepositoryImpl` 구현체 추가
  - 매 1초(`fixedDelay=1000`)마다 `Ohlc1mAggregationStore`의 메모리를 순회하며, 아직 종료되지 않은(Open 상태인) 캔들을 추출해 Redis에 저장하는 `Ohlc1mSnapshotScheduler` 구현

## 📝 Note
### Redis 스냅샷 TTL을 1분(60초)이 아닌 10분으로 설정한 이유
라이브 캔들(1분봉)이므로 직관적으로 1분 TTL이 맞아 보이지만, 실제 트레이딩 시스템 환경에서는 다음과 같은 2가지 치명적인 이슈를 방어하기 위해 여유 있는 TTL(10분)을 부여합니다.

1. **거래량 가뭄(Low Liquidity) 방어 로직**
   만약 새벽 시간에 비트코인 거래가 1분 30초 동안 단 한 건도 발생하지 않았다고 가정해 봅시다.
   - **TTL이 1분일 경우:** 1분이 지나면 Redis에서 이 라이브 캔들 키가 증발(Expired)해버립니다. 누군가 이때 접속해서 API를 호출하면, 1초 전까지 유지되던 캔들 모양이 통째로 날아가고 클라이언트 화면에서 캔들이 아예 사라지는(또는 0으로 나타나는) 치명적인 버그가 발생합니다.
   - **TTL이 10분일 경우:** 거래가 없어서 Redis 키가 갱신(`SET`)되지 않더라도, API는 10분 동안 "마지막으로 거래됐던 캔들의 완성본"을 안전하게 읽어갈 수 있습니다.

2. **Consumer 장애 전파 지연 (Fault Tolerance)**
   만약 `consumer-app` 프로세스가 OOM으로 죽거나 배포 때문에 30초 정도 잠시 다운되었다고 가정해 봅시다.
   - **TTL이 1분일 경우:** 짧은 순단에도 스냅샷이 바로 날아갈 확률이 높아, 의존하고 있던 `api-app`의 조회 결과까지 깨지게 됩니다.
   - **TTL이 10분일 경우:** 컨슈머가 죽더라도 프론트엔드는 이전 스냅샷 상태를 기반으로 차트를 그대로 유지할 수 있으며, 컨슈머가 빠르게 재시작되면 자연스럽게 다시 덮어쓰기가 시작되므로 사용자들은 서버 장애를 거의 느끼지 못하게 됩니다.

결승선에 도달한(Closed) 캔들은 알아서 Flushing 스케줄러가 DB로 넣기 때문에, 10분이라는 긴 TTL은 단순히 **"만약의 사태를 대비한 안전망(Safety Net)"** 역할일 뿐, 메모리나 로직에 나쁜 영향을 주지 않습니다.

## 📌 Related Issue
Closes #61
