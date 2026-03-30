package com.coinflow.monitoring;

import com.coinflow.config.properties.TickConsumerProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.connection.stream.PendingMessage;
import org.springframework.data.redis.connection.stream.PendingMessages;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;

import static com.coinflow.monitoring.constant.MetricConstants.*;

/**
 * Redis Stream의 PEL(Pending Entries List)을 감시하여 장기간 ACK되지 않은 메시지를 로깅합니다.
 * 전문가 리뷰에 따라 임계치는 30초로 설정하며, 현재는 'Logging Only' 전략을 취합니다.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class PelRecoveryWorker {

    private final RedisTemplate<String, String> redisTemplate;
    private final TickConsumerProperties properties;
    private final MetricRecorder metricRecorder;

    // 장기 미처리 메시지 판단 임계치 (30초)
    private static final Duration PEL_THRESHOLD = Duration.ofSeconds(30);

    // 한 번에 조회할 최대 Pending 메시지 수
    private static final int PEL_BATCH_SIZE = 100;

    /**
     * 1분 주기로 PEL을 스캔합니다.
     */
    @Scheduled(fixedDelayString = "${monitoring.pel-check-interval-ms:60000}")
    public void monitorPel() {
        String streamKey = properties.streamKey();
        String group = properties.group();

        try {
            // 최대 Pending 메시지를 조회
            PendingMessages pendingMessages = redisTemplate.opsForStream().pending(streamKey, group, Range.unbounded(), (long) PEL_BATCH_SIZE);

            if (pendingMessages == null || pendingMessages.isEmpty()) {
                return;
            }

            long totalPending = pendingMessages.size();
            long overThresholdCount = 0;

            for (PendingMessage pm : pendingMessages) {
                Duration elapsedTime = pm.getElapsedTimeSinceLastDelivery();
                
                if (elapsedTime.compareTo(PEL_THRESHOLD) >= 0) {
                    overThresholdCount++;
                    log.warn("[PEL-ALERT] Found long-pending message. stream={}, group={}, recordId={}, consumer={}, idleSeconds={}", 
                            streamKey, group, pm.getId(), pm.getConsumerName(), elapsedTime.toSeconds());
                }
            }

            if (overThresholdCount > 0) {
                log.error("[PEL-SUMMARY] Total {} messages are pending over {}s for group {}.", 
                        overThresholdCount, PEL_THRESHOLD.toSeconds(), group);
            } else {
                log.debug("[PEL-CHECK] All {} pending messages are within the threshold.", totalPending);
            }

            // 지연된 메시지 수 메트릭 기록
            metricRecorder.recordValue(STREAM_PEL_COUNT, (double) overThresholdCount, TAG_MODULE, VALUE_MODULE_CONSUMER);

        } catch (Exception e) {
            log.error("Failed to monitor Redis stream PEL. stream={}, group={}, error={}", 
                    streamKey, group, e.getMessage());
        }
    }
}
