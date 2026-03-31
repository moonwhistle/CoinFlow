package com.coinflow.aggregation.service;

import com.coinflow.config.properties.TickConsumerProperties;
import com.coinflow.monitoring.MetricRecorder;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Timer;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import static com.coinflow.monitoring.constant.MetricConstants.REDIS_COMMAND_COUNT;
import static com.coinflow.monitoring.constant.MetricConstants.STREAM_ACK_COUNT;
import static com.coinflow.monitoring.constant.MetricConstants.STREAM_ACK_LATENCY;
import static com.coinflow.monitoring.constant.MetricConstants.TAG_COMMAND;
import static com.coinflow.monitoring.constant.MetricConstants.TAG_FLUSH_REASON;
import static com.coinflow.monitoring.constant.MetricConstants.TAG_MODULE;
import static com.coinflow.monitoring.constant.MetricConstants.VALUE_FLUSH_INTERVAL;
import static com.coinflow.monitoring.constant.MetricConstants.VALUE_FLUSH_SIZE;
import static com.coinflow.monitoring.constant.MetricConstants.VALUE_MODULE_CONSUMER;
import static com.coinflow.monitoring.constant.MetricConstants.VALUE_NA;

/**
 * Redis Stream XACK를 배치로 처리하기 위한 워커입니다.
 * 틱 처리 완료 후 전달된 RecordId들을 큐에 쌓고, 일정 조건(개수 혹은 시간) 충족 시 한 번에 XACK를 호출합니다.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class BatchAckWorker {

    private final RedisTemplate<String, String> redisTemplate;
    private final TickConsumerProperties properties;
    private final MetricRecorder metricRecorder;

    // 배치 설정: 10,000 ~ 100,000 TPS 대응을 위한 최적화 값
    private static final int BATCH_SIZE = 500; 
    private static final long FLUSH_INTERVAL_MS = 50; 

    // 부하 분산을 위한 버퍼 큐 확장 (기존 10,000)
    private final BlockingQueue<RecordId> ackQueue = new LinkedBlockingQueue<>(50000);
    private volatile boolean running = true; // (Point 4) 종료 상태 관리용 플래그
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "batch-ack-worker");
        t.setDaemon(true);
        return t;
    });

    // Zero-Allocation을 위한 Meter 캐싱
    private Timer ackLatencyTimer;
    private Counter ackSuccessCounter;
    private final Map<String, Counter> commandCountersByReason = new ConcurrentHashMap<>();

    @PostConstruct
    public void init() {
        // Meter 핸들 사전 획득 (Runtime 객체 생성 제거)
        this.ackLatencyTimer = metricRecorder.getTimer(STREAM_ACK_LATENCY, TAG_MODULE, VALUE_MODULE_CONSUMER);
        this.ackSuccessCounter = metricRecorder.getCounter(STREAM_ACK_COUNT, TAG_MODULE, VALUE_MODULE_CONSUMER);
        
        // 사유별 Counter 사전 등록
        String[] reasons = {VALUE_FLUSH_SIZE, VALUE_FLUSH_INTERVAL, VALUE_NA};
        for (String reason : reasons) {
            commandCountersByReason.put(reason, metricRecorder.getCounter(REDIS_COMMAND_COUNT, 
                    TAG_COMMAND, "XACK", 
                    TAG_FLUSH_REASON, reason));
        }

        scheduler.scheduleWithFixedDelay(() -> flush(VALUE_FLUSH_INTERVAL), FLUSH_INTERVAL_MS, FLUSH_INTERVAL_MS, TimeUnit.MILLISECONDS);
        log.info("BatchAckWorker initialized with Zero-Allocation Monitoring (BatchSize={}, Interval={}ms)", BATCH_SIZE, FLUSH_INTERVAL_MS);
    }

    @PreDestroy
    public void destroy() {
        log.info("Shutting down BatchAckWorker, flushing remaining ACKs...");
        
        // 1. 새로운 요청 차단 (Point 4)
        this.running = false;

        // 2. 종료 전 마지막 강제 Flush
        try {
            flush(VALUE_NA);
        } catch (Exception e) {
            log.error("Failed to perform final flush during shutdown", e);
        }

        // 3. 스케줄러 종료
        scheduler.shutdown();
        try {
            if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                scheduler.shutdownNow();
            }
        } catch (InterruptedException e) {
            scheduler.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    /**
     * ACK 처리가 필요한 RecordId를 큐에 추가합니다.
     */
    public void addAck(RecordId recordId) {
        if (!running) {
            log.warn("BatchAckWorker is shutting down. Rejecting recordId={}", recordId);
            return;
        }

        if (!ackQueue.offer(recordId)) {
            log.warn("BatchAckWorker queue is full!");
        }
        
        if (ackQueue.size() >= BATCH_SIZE) {
            CompletableFuture.runAsync(() -> flush(VALUE_FLUSH_SIZE), scheduler);
        }
    }

    /**
     * 큐에 쌓인 RecordId들을 한 번에 XACK 처리합니다.
     */
    private synchronized void flush(String reason) {
        if (ackQueue.isEmpty()) {
            return;
        }

        List<RecordId> batch = new ArrayList<>(BATCH_SIZE);
        ackQueue.drainTo(batch, BATCH_SIZE);

        if (!batch.isEmpty()) {
            String streamKey = properties.streamKey();
            String group = properties.group();
            RecordId[] ids = batch.toArray(new RecordId[0]);

            try {
                // 핸들을 직접 사용하여 런타임 객체 생성 최소화
                ackLatencyTimer.record(() -> {
                    redisTemplate.opsForStream().acknowledge(streamKey, group, ids);
                    ackSuccessCounter.increment(batch.size());
                    
                    Counter commandCounter = commandCountersByReason.get(reason);
                    if (commandCounter != null) {
                        commandCounter.increment();
                    }
                });
                log.trace("Flushed {} ACKs in batch (reason={})", batch.size(), reason);
            } catch (Exception e) {
                log.error("Failed to perform Batch XACK. stream={}, group={}, reason={}", streamKey, group, reason, e);
            }
        }
    }
}
