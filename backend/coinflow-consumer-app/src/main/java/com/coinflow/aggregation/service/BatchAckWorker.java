package com.coinflow.aggregation.service;

import com.coinflow.config.properties.TickConsumerProperties;
import com.coinflow.monitoring.MetricRecorder;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;

import static com.coinflow.monitoring.constant.MetricConstants.TAG_COMMAND;
import static com.coinflow.monitoring.constant.MetricConstants.REDIS_COMMAND_COUNT;
import static com.coinflow.monitoring.constant.MetricConstants.STREAM_ACK_COUNT;
import static com.coinflow.monitoring.constant.MetricConstants.STREAM_ACK_LATENCY;
import static com.coinflow.monitoring.constant.MetricConstants.TAG_FLUSH_REASON;

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

    // 배치 설정 (추후 프로퍼티화 가능)
    private static final int BATCH_SIZE = 50;
    private static final long FLUSH_INTERVAL_MS = 100;

    private final BlockingQueue<RecordId> ackQueue = new LinkedBlockingQueue<>(10000);
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "batch-ack-worker");
        t.setDaemon(true);
        return t;
    });

    @PostConstruct
    public void init() {
        scheduler.scheduleWithFixedDelay(() -> flush("interval"), FLUSH_INTERVAL_MS, FLUSH_INTERVAL_MS, TimeUnit.MILLISECONDS);
        log.info("BatchAckWorker initialized (BatchSize={}, Interval={}ms)", BATCH_SIZE, FLUSH_INTERVAL_MS);
    }

    @PreDestroy
    public void destroy() {
        log.info("Shutting down BatchAckWorker, flushing remaining ACKs...");
        scheduler.shutdown();
        try {
            if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                scheduler.shutdownNow();
            }
        } catch (InterruptedException e) {
            scheduler.shutdownNow();
        }
        flush("shutdown");
    }

    /**
     * ACK 처리가 필요한 RecordId를 큐에 추가합니다.
     */
    public void addAck(RecordId recordId) {
        if (!ackQueue.offer(recordId)) {
            log.warn("BatchAckWorker queue is full! Immediate fallback might be needed.");
            // 큐가 가득 찼을 경우에 대한 폴백은 현재 구현하지 않고 로그만 남김 (부하 조절 필요)
        }
        
        if (ackQueue.size() >= BATCH_SIZE) {
            // 개수 기반 즉시 트리거 (스케줄러와 경합할 수 있으나 flush() 내에서 동기화됨)
            CompletableFuture.runAsync(() -> flush("size"), scheduler);
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
                metricRecorder.recordTime(STREAM_ACK_LATENCY, () -> {
                    redisTemplate.opsForStream().acknowledge(streamKey, group, ids);
                    metricRecorder.increment(STREAM_ACK_COUNT, batch.size()); // 처리된 메시지 총합
                    metricRecorder.increment(REDIS_COMMAND_COUNT, 
                            TAG_COMMAND, "XACK", 
                            TAG_FLUSH_REASON, reason); // 실제 Redis 명령 1회 + 사유 기록
                });
                log.trace("Flushed {} ACKs in batch (reason={})", batch.size(), reason);
            } catch (Exception e) {
                log.error("Failed to perform Batch XACK for {} records. stream={}, group={}, reason={}", 
                        batch.size(), streamKey, group, reason, e);
            }
        }
    }
}
