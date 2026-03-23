환경: AWS T2.micro
메모리: 512MB
목표: 1분/5분/30분 캔들, 심볼(종목) 100개 확장 고려
사용자: 100명 예상


안녕하세요 오늘은 로컬캐시를 이용해 차트 조회 속도를 높이는 과정에 대해서 설명하려합니다.





우선, 차트 조회 API가 어떻게 동작하는지 설명드리겠습니다.

차트 조회 API 동작 방식

차트 조회 API 흐름
그림과 같이 Consumer 모듈이
현재 진행중인 캔들은 -> Redis에 저장
진행이 끝난 캔들은 -> DB에 저장
이후 차트 조회 API가 호출되면
Redis에 저장된 캔들을 -> Redis에서 조회
DB에 저장된 캔들을 -> DB에서 조회
Redis와 DB의 캔들을 합쳐서 -> 응답
하는 구조로 되어있습니다.

쉽게 말하면 DB 에서 마감된 캔들 데이터 + Redis 에서 현재 진행중인 캔들 데이터를 합쳐 반환하게 됩니다.





그렇다면 현재 차트 조회 API 응답 속도는 어떨까요?


현재 응답 속도



차트 조회 API 응답 속도


1분봉 120개를 요청할 때의 응답속도입니다.



Postman 을 통해 요청을 보내봤는데 2.8초...진짜 너무 느립니다.

이 속도로 차트를 실시간으로 보기에는 무리가 있다고 판단했습니다.



왜 이렇게 느릴까요??

응답이 느린 이유를 한 번 분석해봤습니다.

문제점 분석
이유는 바로, 매 요청마다 DB를 조회하기 때문입니다. 왜 매 요청마다 DB를 조회하는 것이 느릴까요?


thread - DB


모든 부분이 다 Latency 이긴 하지만, 저 빨간색 네모 부분이 가장 지연이 많이 발생하는 구간입니다. 바로 3~7번 네트워크 I/O 구간입니다.



해당 부분으로 인해 매 요청마다 DB 조회하는 로직에 지연이 생깁니다.





참고
참고로 현재 DB에서 쿼리를 실행하는 시간은 1ms도 걸리지 않습니다.
차트 저장 과정에서 (symbolid, bucketTime)에 유니크 인덱스를 걸어주었기 때문입니다.(중복 저장 방지를 위한 제약조건)




인덱스 적용 후 실행계획

현재 차트 조회 실행계획을 보게 되면, Index Scan을 통해 쿼리가 실행되고 있는 것을 알 수 있습니다.
실행 시간은 1ms도 걸리지 않죠.






인덱스 적용 전 실행계획

반면에 인덱스 적용 전에는 Seq Scan을 통해 쿼리가 실행되었고, 실행 시간은 7.6ms 정도 걸렸습니다.
(PostgreSQL Seq Scan == MySQL Full Scan)



(저장 데이터 800개 기준입니다.)









저는 이러한 데이터 지연 문제를 해결하기 위해 데이터 저장 구조를 분석하여 캐시를 적용했습니다.

차트 데이터 저장 구조 분석과 캐시 적용


## 현재 차트 데이터 저장 구조와 TTL 전략 ##

Consumer 모듈이 Tick 데이터를 Consume 하여 저장하는 방식은 다음과 같습니다.



현재 진행중인 캔들은 -> Redis에 저장
진행이 끝난 캔들은 -> DB에 저장


데이터 저장 구조가 깔끔했기 때문에 조회하는 전략은 간단했습니다.

Closed Candle + Current Candle 형식으로 반환하고자 했습니다.



다만 여기서 신경써야 할 부분은 TTL 이었습니다.

1m/5m/30m 차트가 존재했기에 다르게 TTL 을 넣어주는 것이 맞다고 생각했습니다.



그래서 [1m 차트의 경우 1분마다 봉이 마감 -> 따라서 TTL = 1분] 요런 방식으로 5m/30m 차트도 TTL 을 다르게 5분/10분으로 정해주었습니다.





결과적으로 차트당 한 번만 조회하면, 다음에는 캐시  데이터를 조회할 수 있는 전략을 세웠습니다.





## 캐시 적용 ##

가장 먼저, 캐시는 L1 캐시인 Caffeine을 선택했습니다.



실시간성이 중요한 차트 서비스였기에, L2 계층인 Redis 보다 조금이라도 빠름 + 단일 서버에 최대 종목 100개 + 차트 3개(1m/5m/30m)인 작은 서비스였기에 Caffeine 이 합리적인 선택이라고 생각했습니다. 또한, 단일 서버이기 때문에 굳이 Redis를 사용할 필요가 없었습니다.




Cache-Aside 전략 적용


캐시를 적용한 전체적인 흐름은 위 그림과 같았습니다.



캐시를 먼저 조회하고 캐시에 데이터가 없다면 DB를 조회하는 Cache-aside 전략을 선택했습니다. (Read-through 전략을 사용할 경우 캐시 서버가 다운되었을 때 차트 데이터를 하나도 받아올 수 없다고 생각하였습니다.)



이렇게 적용한 캐시는 API 응답을 얼마나 줄일 수 있었을까요?




캐시 적용 차트 조회 latency


엄청난 차이입니다. 캐시를 적용하고나서 281ms -> 15ms 로, 1m봉 120개 데이터 기준 약 18~19배 성능 향상을 이루어낼 수 있었습니다.





## 사용자 100명 예상 부하테스트 ##

자 이제 캐시를 적용해 성능 향상을 이끌어 냈으니, 예상 사용자 100명을 기준으로 부하 테스트를 진행해볼 차례입니다.

테스트는 100명을 기준으로 k6를 이용해 부하 테스트를 진행하였습니다.



완벽할줄 알았던 캐시 적용에서 문제가 발생했습니다.



1. Thundering herd(= cache stampede) 문제 발생

(cache stampede 보다 thundering herd 가 어감이 더 좋아서 이렇게 명시하겠습니다 ㅎㅎ..)




부하테스트 로그- thundering herd




위 로그는 데이터 캐싱 이전 시점에서 Cache Miss 가 발생한 로그입니다.

M1(=1분봉) 데이터를 올바르게 요청하였지만, 캐시가 존재하지 않는 순간에 요청이 몰리며 Cache Miss 가 발생하였습니다.



해당 로그로 파악할 수 있는 문제점은, 캐시를 도입했더라도 Cache Miss 상황에서는 DB에 부하가 집중될 수 있다는 점입니다. 





2. Cache penetration 문제 발생


부하테스트 로그 - penetration


두 번째 문제입니다.



존재하지 않는 심볼(9999)에 대해 요청을 보냈음에도, 처음에는 Cache Miss ->  이후 Cache Hit 흐름으로 연결되면서 존재하지 않는 데이터에 대해서도 캐시가 생성되는 문제가 발생하였습니다.



(물론 해당 데이터를 조회할 때 NOT_FOUNT_SYMBOL 예외처리되며 데이터는 조회되지 않았습니다.)







결과적으로 현재 적용한 로컬캐시(Caffeine)는 T2.micro 상황에서 Cache miss 로 인해 DB Connection Pool Timeout 발생 -> CPU 가 폭증하며 서버 다운될 수도 있고, 여전히 해결하고자 했던 latency 문제는 특정 부분에서 존재하게 됩니다. 또한 존재하지 않는 Symbol에 대해 캐싱될 수도 있습니다.







이제, 아무 생각없이 캐시를 적용하였던 순간을 반성하며 해당 문제를 해결해보겠습니다.

캐싱 이슈 해결
캐싱 이슈를 해결하기에 앞서, 현재 어떤 구조때문에 Thundering herd, Cache penetration 문제가 생기는지 분석해보겠습니다.

현재 캐싱 키 전략 
현재 캐싱 키 전략은 다음과 같습니다.


현재 데이터 저장 구조


1m/5m/30m 각각 분에 따라 TTL 은 알맞게 설정되며 위와 같은 형식으로 데이터가 저장됩니다.





여기서 알 수 있는 점은,



1. 캐시 데이터는 특정 분마다 생성된다는 점 (키 자체가 분산됨)

2. 이에 따라, 캐시 키가 동시에 만료되어서 Thundering herd 가 일어나지는 않는다는 점.

3. 결과적으로 Jitter를 의도적으로 적용할 필요가 없다는점 입니다.





그렇다면 애초에 캐시 값을 생성할 때 몰리는 요청들을 제어해야 한다는 것인데, 어떤 방식으로 해결할 수 있었을까요?

Caffeine 분석

캐시 값을 생성할 때 몰리는 요청을 제어하려면 결국 **"같은 키에 대해 1명만 DB를 조회하고, 나머지는 그 결과를 기다리게 하는"** 방법이 필요합니다.

이를 구현하는 방법은 여러 가지가 있습니다.

1. **synchronized 블록**: 캐시 조회 로직 전체에 synchronized를 거는 방법. 하지만 이 경우 서로 다른 키의 요청끼리도 대기해야 하므로 성능이 크게 떨어집니다.
2. **Key별 Lock 직접 구현**: `ConcurrentHashMap<String, Lock>`을 만들어 키마다 별도의 Lock을 관리하는 방법. 동작은 하지만 Lock의 생성/해제/메모리 누수 관리를 직접 해야 하는 복잡도가 있습니다.
3. **캐시 라이브러리가 제공하는 기능 활용**: 라이브러리 자체가 이미 이 문제를 해결하는 API를 제공한다면 가장 안전하고 간결한 방법이 됩니다.

저는 이미 사용 중인 Caffeine 라이브러리가 이런 동시성 제어 기능을 내장하고 있는지 분석해 보기로 했습니다.


### 1. 현재 코드가 사용하는 메서드: getIfPresent() + put()

현재 캐시 조회 코드(`CaffeineOhlcChartStore`)를 살펴보겠습니다.

```java
// CaffeineOhlcChartStore.java - 캐시 조회
CachedChart cached = cache.getIfPresent(key);  // 단순히 "있나 없나"만 확인
if (cached != null) {
    return Optional.of(cached.snapshots());    // Hit → 반환
}
return Optional.empty();                        // Miss → 빈 값 반환
```

```java
// OhlcChartService.java - 비즈니스 로직
chartStore.get(symbolId, interval, candles, endExclusive)
    .orElseGet(() -> loadAndCache(...));  // Miss 시 → DB 조회 후 put()
```

이 구조에서 `getIfPresent()`는 캐시에 데이터가 있는지 **읽기만** 할 뿐, 동시 접근에 대한 어떠한 제어(Lock)도 하지 않습니다.

따라서 50명이 동시에 요청하면, 50명 모두 `getIfPresent()`에서 null을 받고 각자 `loadAndCache()`를 호출하여 **50번의 DB 쿼리가 발생**합니다.



### 2. Caffeine의 동시성 제어 메커니즘 분석

Caffeine은 내부적으로 Java의 `ConcurrentHashMap`을 기반으로 구현되어 있습니다.

핵심은 `cache.get(key, mappingFunction)` 메서드에 있습니다. 이 메서드는 내부적으로 `ConcurrentHashMap.computeIfAbsent()`와 동일한 방식으로 동작하며, **키(key) 단위로 락(Lock)을 걸어 동시 접근을 제어**합니다.

동작 과정은 다음과 같습니다.

1. 50개의 스레드가 동시에 `cache.get(key, loader)`를 호출합니다.
2. Caffeine 내부에서 해당 key에 대해 **하나의 스레드만** 락을 획득하고 loader 함수(DB 조회)를 실행합니다.
3. 나머지 49개의 스레드는 해당 key의 연산이 완료될 때까지 **대기(Blocking)** 합니다.
4. 첫 번째 스레드가 DB 조회 결과를 반환하면, 대기 중이던 49개의 스레드도 **DB 조회 없이** 동일한 캐시 값을 반환받습니다.

결과적으로 DB에는 **단 1번의 쿼리**만 실행됩니다.

즉, `getIfPresent()` + `put()`을 수동으로 분리하면 Caffeine은 개입할 수 없지만, `get(key, function)`으로 **로딩 책임을 Caffeine에게 위임**하면 내부 동시성 제어가 작동합니다.



### 3. 해결: get(key, mappingFunction) 적용

위 분석 결과를 바탕으로, 기존의 수동 Cache-Aside 패턴을 Caffeine의 `get(key, mappingFunction)` 방식으로 변경했습니다.

**변경 전 (Thundering herd 발생)**
```java
// CaffeineOhlcChartStore - 조회와 저장이 분리되어 있음
CachedChart cached = cache.getIfPresent(key);   // 락 없이 조회
// ...
cache.put(key, new CachedChart(...));            // 수동 저장

// OhlcChartService - 50명이 동시에 loadAndCache()에 진입 가능
chartStore.get(...).orElseGet(() -> loadAndCache(...));
```

**변경 후 (Thundering herd 방어)**
```java
// CaffeineOhlcChartStore - 조회와 로딩을 Caffeine에게 위임
List<OhlcCandleSnapshot> result = cache.get(key, k -> {
    // Caffeine이 이 key에 대해 락을 걸고, 단 1개의 스레드만 이 로직을 실행함
    return loadClosedCandles(symbolId, interval, candles, endExclusive);
});
```

이 변경으로 인해 **Cache-Aside의 안정성**(캐시 장애 시 DB fallback 가능)은 그대로 유지하면서도, 동시 요청이 몰리는 Cache Miss 시점에서 **DB 쿼리를 단 1회로 제한**할 수 있게 되었습니다.

