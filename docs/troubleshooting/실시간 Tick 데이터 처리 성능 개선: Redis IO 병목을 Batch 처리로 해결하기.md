(글을 읽으시며 잘못된 부분이나 아쉬운 부분이 있다면 댓글 부탁드립니다!!)



환경: AWS T2.micro
메모리: 512MB
목표: 1분/5분/30분 캔들, 심볼(종목) 100개 확장 고려
사용자: 100명 예상
tps: 100개 종목 x 한 종목당 100tps = 10000 tps﻿


안녕하세요 오늘은 Tick 데이터 처리 과정에서의 병목 지점중 한 부분인 Redis I/O 문제를 해결한 내용에 대해 이야기 해보려고 합니다.



일단 설명드리기에 앞서, Tick 데이터 데이터 흐름, 병목 지점 분석에 관한 자세한 부분은 이전 글을 참고해주시면 감사하겠습니다!

## 데이터 흐름과 Redis I/O 병목 지점
그래도 간단히 배경 설명을 하며 시작해보겠습니다.



데이터 흐름
현재 데이터 흐름은 다음과 같습니다.


현재 Data Flow


1. 외부 API 를 통해 Tick 데이터를 호출합니다.

2. 호출한 Tick 데이터를 Collector 모듈이 Message Queue(Redis Stream)에 publish 합니다.

3. Consumer 모듈은 Message Queue 로 부터 해당 데이터를 읽어와서 뿌려줍니다.



여기까지가 전체적인 데이터 Flow 입니다.





그렇다면 이러한 흐름 속에서 Tick 데이터가 어떻게 처리되고 있기에 이러한 병목이 생겼는지 알아보겠습니다.



## Redis I/O 병목지점
현재 시스템은 평균 10,000tps 정도의 부하를 견뎌야 합니다.


TIck 한 건 처리 프로세스


하지만 이 그림과 같이 현재 로직은 Tick 1건 당 [READ, XACK] I/O 두 번이 일어나고 있는 구조였고, 해당 부분에서 병목이 생길 수 있다고 판단하였습니다.



그래서 실제로 모니터링을 진행하였고, redis 명령어 호출 횟수와 XACK letency가 차지하는 부분이 상당했습니다.

### Redis I/O 병목 해결 방안
따라서, 이러한 구조를 해결하기 위해 정책적인 부분부터 살펴봤습니다. 올바른 해결 방안을 위해서는 서비스의 목적인 뭔지를 제대로 파악하고, 그 과정에서 서비스 정책을 고려하는 것이 우선이라고 생각했습니다.



### 정책적인 관점에서의 고민
우선, 해당 서비스는 코인 데이터를 실시간으로 제공하기 위한 목적을 가지고 있습니다. 따라서, 무엇보다 실시간성이 중요했습니다.

그래서 정확하고 빠른 현재가를 제공해야 했습니다.



그럼 여기서 고려해볼 수 있는 점이 하나 생기게 됩니다. 바로, Tick 데이터는 들어오는 즉시 처리해야 한다는 점입니다. 

따라서, Conusmer 그룹을 통해 Tick 을 읽어오는 Redis I/O는 필수적인 부분이었습니다.



그렇다면 반대로 Tick 데이터를 읽었다는 XACK 은 바로바로 전달해줄 필요가 있을까요?

정답은 "아니다" 입니다.

ACK는 [메시지를 정상적으로 처리했으니 더 이상 재전송하지 않아도 된다]는 신호일 뿐, 실시간성과는 직접적인 관련이 없습니다.



따라서 현재 시스템에서 생기는 Latency 를 줄이기 위해서는 해당 XACK 부분에 대한 I/O를 줄이는 것이 맞다고 생각했습니다.



기술적인 관점에서의 고민
XACK I/O를 줄이는 방법은 간단했습니다. Batch 단위로 ACK 을 처리하는 것이 핵심이었습니다.



Batch ACK를 적용하면서 가장 중요하게 고려했던 부분은 [언제 ACK를 수행할 것인가]였습니다. 많이 쌓았다가 한 번에 처리하면 그만큼 latency 는 줄일 수 있겠지만, ACK 지연으로 인해 Pending List가 증가하고 장애 시 재처리 범위가 커질 수 있었습니다.



그래서 두 가지 기준을 세웠습니다.



첫 번째는 개수 기반 batch 처리입니다. 일정 개수 이상의 메시지가 쌓이면 즉시 ACK를 수행하도록 하여, 과도한 버퍼링을 방지했습니다.
두 번째는 시간 기반 batch 처리입니다. 시스템 부하가 낮은 상황에서도 ACK가 지나치게 늦어지지 않도록, 일정 시간마다 강제로 버퍼를 비우도록 설계했습니다.
이러한 두 가지 기준을 세워서 batch 로직을 구현하고자 하였고, 다음 그림과 같은 최종 flow 가 만들어졌습니다.




Batch XACK Flow




1. Consumer 모듈이 메시지를 읽고 Batch Worker에 전달합니다.

2. Batch Worker 는 특정 조건(시간 / 개수)이 만족되면 Redis Stream에 읽은 데이터를 전달합니다.

Batch XACK 구현
핵심 로직은 BatchAckWorker라는 전용 서비스에서 담당합니다. 처리된 메시지 ID들을 큐에 쌓아두었다가, 조건이 충족되면 Redis의 XACK 명령을 호출합니다.

@Service
public class BatchAckWorker {
    private static final int BATCH_SIZE = 500;  // 500개씩 모아서 처리
    private final BlockingQueue<RecordId> ackQueue = new LinkedBlockingQueue<>(50000);

    @PostConstruct
    public void init() {
        // [조건 1] 50ms마다 주기적으로 큐를 비움 (시간 기반)
        scheduler.scheduleWithFixedDelay(() -> flush(), 50, 50, TimeUnit.MILLISECONDS);
    }

    public void addAck(RecordId recordId) {
        ackQueue.offer(recordId);
        // [조건 2] 500개가 쌓이면 즉시 비움 (개수 기반)
        if (ackQueue.size() >= BATCH_SIZE) {
            CompletableFuture.runAsync(() -> flush());
        }
    }

    private synchronized void flush() {
        List<RecordId> batch = new ArrayList<>();
        ackQueue.drainTo(batch, BATCH_SIZE);

        if (!batch.isEmpty()) {
            // Redis ACK 일괄 전송
            redisTemplate.opsForStream().acknowledge(streamKey, group, batch.toArray(new RecordId[0]));
        }
    }
}
(Batch size, Batch time 모두 1개의 Consumer group 에 기반하여 결정된 수치입니다.)



## Batch size 결정 기준 ##

Batch size를 결정하는데 있어서 가장 중요한 점은, 네트워크 I/O 효율성이었습니다. 따라서, 실제 Redis 프로토콜(RESP) 기준으로 패킷 크기를 계산해봤습니다.



Redis에서 RecordId는 문자열 형태(timestamp-sequence)로 직렬화되어 전송되며, 하나의 ID는 약 20Byte 내외의 네트워크 페이로드를 차지합니다. 이를 기준으로 500개의 ID를 묶을 경우 약 10KB 수준의 요청이 생성됩니다.



그리고 네트워크 환경에서 MTU는 약 1,500Byte인걸 생각해보면, 10KB 데이터는 약 7~8개의 패킷으로 분할되어 전송됩니다. Tick 하나 당 20byte 패킷을 1500byte 에 넣어서 보냈던 것을 생각하면 훨씬 효율적입니다.



하지만 반대로 Batch size를 지나치게 크게 설정할 경우, 다음과 같은 문제가 발생할 수 있습니다.

네트워크 payload 증가로 인한 지연
ACK 지연에 따른 Pending 증가
장애 발생 시 재처리 범위 확대
따라서 저는 10,000tps 환경에서 500 batch size 를 두어, redis 에 초당 20번의 통신만 하게 하는 구조를 선택하게되었습니다.

(통신 한 번당 0.5ms 로 잡아도, 초당 10ms 이기 때문에 실시간성을 해치지 않는다고 판단했습니다.)



++ 추가적으로 Queue Size 를 50,000 으로 설정해서 10,000tps 상황에서 최대 5초간의 지연은 견딜 수 있는 구조를 만들었습니다.





## Batch time 결정 기준 ##

Batch size만으로는 안정적인 처리를 보장할 수 없습니다. 트래픽이 낮은 상황에서는 Batch size를 채우는 데 오랜 시간이 걸릴 수 있기 때문입니다. 따라서 Batch time을 함께 도입했습니다.



조건부 타이머 방식과 고정 주기 방식 중 선택이 필요했는데, T2.micro(1 vCPU) 환경에서는 타이머를 반복적으로 생성/취소하는 방식이 오히려 CPU 오버헤드를 유발할 수 있다고 판단했습니다. 따라서 더 가볍고 예측 가능한 고정 스케줄 방식을 선택했습니다.



그리고 Batch time은 50ms로 설정했습니다.



10,000 TPS 기준에서 500건이 약 50ms 만에 쌓이기 때문에, 고부하 상황에서는 Batch size 조건에 의해 자연스럽게 flush가 발생합니다. 반대로 저부하 상황에서는 Batch time이 동작하여, 최대 50ms 이내에 ACK가 수행되도록 보장합니다.



예를 들어 최악의 20,000 TPS 상황에서도 500건은 약 25ms 만에 채워지므로, 실제 Redis XACK 호출은 초당 약 40 ~ 100회 수준으로 유지됩니다. (물론 정말 최악일 경우 예측할 수 없을 만큼 Redis I/O가 발생할 수 있으므로 이 부분은 모니터링을 해봐야 할 것 같습니다.)

Batch XACK 테스트
자 이렇게 Batch XACK 로직을 구현했으니, 정말 효과가 있는지 비교해볼 차례입니다. 10,000TPS 기준 부하테스트를 진행하였고, 25분동안 관찰한 결과를 보여드리겠습니다.

(실시간성이 중요한 파이프라인이기 때문에 P99지표를 중심으로 설명드리겠습니다.)



E2E Latency
collector - redis stream - consumer 로 이어지는 e2e 테스트 지표입니다.


최적화 전 | 최적화 후


최적화 전의 경우, 평균 약 2ms 를 유지하나 P99 지표가 불안정한 모습을 보실 수 있습니다. 이렇게 P99 지표가 튄다는 것은 하나의 Tick 데이터 병목으로 인해 다음 Tick 데이터 처리가 밀릴 수 있음을 의미합니다.



최적화 후의 경우, 평균 약 1.65ms 를 유지하고 비교적 안정된 P99 지표를 보여줍니다. 최적화 전과 비교했을 때 평균치도 많이 개선이 된 모습도 보입니다.



이를 통해, 특정 데이터로 인해 전체 처리 흐름이 지연되는 현상이 개선되고, 결과적으로 시스템 전체의 처리 일관성과 안정성이 크게 향상되었음을 알 수 있습니다.



XACK Latency
XACK latency 지표입니다. Redis Stream 에 XACK 을 보내고 확인 응답을 받기까지의 시간을 나타냅니다.﻿


최적화 전 | 최적화 후


겉으로 보기에는 별 차이가 없어 보일 수 있지만 사실 엄청난 차이입니다. 이유는 다음과 같습니다.



최적화 전에는 Tick 데이터 1건마다 XACK이 수행되는 구조였기 때문에, 초당 약 10,000번의 네트워크 왕복이 발생했습니다. 개별 요청의 latency는 낮았지만, 지속적인 Redis I/O 와 이로 인한 컨텍스트 스위칭이 발생했습니다.



반면 최적화 이후에는 여러 메시지를 묶어 Batch 단위로 XACK을 수행하도록 변경했습니다. 그 결과 Redis 호출 횟수는 초당 약 20회 수준으로 감소했으며, 더 큰 데이터를 처리함에도 불구하고 latency는 기존과 유사하거나 더 안정적인 모습을 보입니다.



특히 주목할 점은 P99 지표의 안정화입니다. 기존에는 간헐적인 spike가 발생했지만, Batch 처리 이후에는 이러한 변동성이 크게 줄어들었습니다.



결과적으로 Redis I/O 횟수를 약 99% 이상 줄이면서도, latency는 유지하거나 오히려 더 안정적으로 개선할 수 있었습니다.

## Batch XACK 도입으로 인한 장애 상황 분석과 해결방안
배치 처리는 성능 면에서 정말 압도적이지만, 이로 인해 새로운 고민거리가 생겼습니다.



원인: 메시지 중복 처리 가능성 
가장 큰 문제는 메시지를 중복으로 처리할 수 있다는 점이었습니다.




중복 재처리 가능성


메시지 처리한 후 한 번에 batch XACK를 날렸지만 네트워크 문제로 인해 유실된다면, Redis 입장에서는 500개 모두 아직 처리되지 않은 Pending 상태로 남게 됩니다. 이후 해당 메시지는 다시 컨슈머에게 전달되어 중복 처리될 위험이 있었습니다.



해결: Idempotency key 를 활용한 필터링 적용
해당 문제를 해결하기위해 redis stream 을 통해 주어지는 recordID 를 Idempotency key로 활용하기로 결정했습니다.

private final Cache<String, Boolean> processedIdCache = Caffeine.newBuilder()
        .maximumSize(100_000)           // 최대 10만 건 기억 
        .expireAfterWrite(Duration.ofMinutes(1)) // 1분 후 자동 삭제
        .build();

private boolean isDuplicate(String symbol, RecordId incomingId) {
    String idValue = incomingId.getValue();
    
    // 캐시에 ID가 존재하면 이미 성공적으로 처리된 중복 데이터임
    if (processedIdCache.getIfPresent(idValue) != null) {
        return true; 
    }
    
    // 처리가 시작된 ID는 캐시에 기록
    processedIdCache.put(idValue, Boolean.TRUE);
    return false;
}


근본적인 목적이 Redis I/O를 줄이는 것이었기 때문에 L1 Caffeine 캐시를 Idempotency key 저장소로 사용하였습니다.
또한, 데이터가 계속 쌓이는 만큼 LRU 정책을 통해, 데이터 증가를 방지하였습니다.



따라서 Consumer 가 Redis Stream으로부터 데이터를 받아오면, 캐시를 통해 한 번 검증하고 로직을 이어나가는 방식으로 설계하였습니다. 이를 통해 중복 메시지 처리를 방지할 수 있었습니다.