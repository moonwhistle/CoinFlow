package com.coinflow.monitoring;

import com.coinflow.config.properties.TickConsumerProperties;
import com.coinflow.monitoring.constant.MetricConstants;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.connection.stream.PendingMessage;
import org.springframework.data.redis.connection.stream.PendingMessages;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Redis Stream의 PEL(Pending Entries List)을 주기적으로 감시합니다.
 * 처리 시작 후 일정 시간(30초) 이상 ACK 되지 않은 메시지를 찾아 로그로 기록합니다.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class PelRecoveryWorker {

    private final RedisTemplate<String, String> redisTemplate;
    private final TickConsumerProperties properties;
    private final MetricRecorder metricRecorder;

    // PEL 감지 임계치 (30초)
    private static final Duration PENDING_THRESHOLD = Duration.ofSeconds(30);

    /**
     * 1분마다 PEL을 감시하여 장기 미결 처리된 메시지를 식별합니다.
     */
    @Scheduled(fixedDelayString = "${monitoring.pel-check-interval-ms:60000}")
    public void checkPendingEntries() {
        String streamKey = properties.streamKey();
        String group = properties.group();

        try {
            // Redis 명령 횟수 기록 (XPENDING)
            metricRecorder.increment(MetricConstants.REDIS_COMMAND_COUNT, 
                    MetricConstants.TAG_COMMAND, "XPENDING",
                    MetricConstants.TAG_FLUSH_REASON, "none");

            // 1. 전체 펜딩 요약 정보 확인
            PendingMessages summary = redisTemplate.opsForStream().pending(streamKey, group);
            if (summary == null || summary.isEmpty()) {
                return;
            }

            long totalPending = summary.getCount();
            if (totalPending > 0) {
                log.debug("[PEL] Detected {} pending messages for group {}", totalPending, group);
                
                // 2. 임계치(30초) 이상 지연된 구체적 메시지 목록 조회
                // IDLE 30s 이상인 메시지 최대 100건 조회
                PendingMessages longPending = redisTemplate.opsForStream().pending(streamKey, group, Range.unbounded(), 100);
                
                long longPendingCount = 0;
                for (PendingMessage msg : longPending) {
                    // getElapsedTimeMillis()는 메시지가 PEL에 머문 총 시간
                    // 단, Redis 6.2+ XPENDING IDLE 필드를 직접 활용하는 것이 더 정확함
                    // Spring Data Redis의 PendingMessage.getElapsedTime()은 last-delivered-time 기준임
                    if (msg.getElapsedTime().compareTo(PENDING_THRESHOLD) >= 0) {
                        log.warn("[PEL ALERT] Message stuck in PENDING for over {}s: ID={}, Consumer={}, IdleTime={}ms",
                                PENDING_THRESHOLD.toSeconds(),
                                msg.getIdAsString(),
                                msg.getConsumerName(),
                                msg.getElapsedTime().toMillis());
                        longPendingCount++;
                    }
                }

                if (longPendingCount > 0) {
                    log.error("[PEL SUMMARY] Total {} messages are stuck in PENDING for group {}", longPendingCount, group);
                    // (선택 사항) 지연 메시지 발생에 대한 메트릭 연동 가능
                }
            }
        } catch (Exception e) {
            log.error("Failed to check Redis stream PEL. stream={}, group={}, error={}", 
                    streamKey, group, e.getMessage(), e);
        }
    }
}
