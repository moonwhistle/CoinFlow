package com.coinflow.monitoring;

import com.coinflow.config.properties.TickConsumerProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.stream.StreamInfo;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import static com.coinflow.monitoring.constant.MetricConstants.*;

/**
 * Redis Stream의 컨슈머 그룹 Lag(Backlog) 수치를 주기적으로 수집하여 메트릭으로 기록합니다.
 * 또한 모니터링을 위한 Redis 명령(XINFO) 호출 횟수도 기록합니다.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class StreamLagMonitorWorker {

    private final RedisTemplate<String, String> redisTemplate;
    private final TickConsumerProperties properties;
    private final MetricRecorder metricRecorder;

    /**
     * 주기적으로 Lag 수치를 확인합니다. (기본 5초)
     */
    @Scheduled(fixedDelayString = "${monitoring.lag-check-interval-ms:5000}")
    public void monitorLag() {
        String streamKey = properties.streamKey();
        String group = properties.group();

        try {
            // Redis 명령 횟수 기록 (XINFO)
            metricRecorder.increment(REDIS_COMMAND_COUNT, 
                    TAG_COMMAND, "XINFO",
                    TAG_FLUSH_REASON, VALUE_NA);

            StreamInfo.XInfoGroups groups = redisTemplate.opsForStream().groups(streamKey);
            if (groups != null) {
                groups.stream()
                        .filter(g -> g.groupName().equals(group))
                        .findFirst()
                        .ifPresent(g -> {
                            Object lagObj = g.getRaw().get("lag");
                            Long lag = null;
                            if (lagObj instanceof Long) {
                                lag = (Long) lagObj;
                            } else if (lagObj instanceof Number) {
                                lag = ((Number) lagObj).longValue();
                            }

                            if (lag != null) {
                                metricRecorder.recordValue(STREAM_BACKLOG_COUNT, lag.doubleValue(), 
                                        TAG_MODULE, VALUE_MODULE_CONSUMER);
                                log.debug("Redis Stream Lag monitored: stream={}, group={}, lag={}", 
                                        streamKey, group, lag);
                            } else {
                                log.warn("Stream lag information is not available for group: {}. Please check Redis version (7.0+ required).", group);
                            }
                        });
            }
        } catch (Exception e) {
            log.error("Failed to monitor Redis stream lag. stream={}, group={}, error={}", 
                    streamKey, group, e.getMessage());
        }
    }
}
