package com.coinflow.monitoring;

import com.coinflow.config.properties.TickConsumerProperties;
import io.micrometer.core.instrument.Counter;
import jakarta.annotation.PostConstruct;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.stream.StreamInfo;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import static com.coinflow.monitoring.constant.MetricConstants.REDIS_COMMAND_COUNT;
import static com.coinflow.monitoring.constant.MetricConstants.STREAM_BACKLOG_COUNT;
import static com.coinflow.monitoring.constant.MetricConstants.STREAM_BACKLOG_RETENTION_RATIO;
import static com.coinflow.monitoring.constant.MetricConstants.STREAM_RETENTION_WARNING_COUNT;
import static com.coinflow.monitoring.constant.MetricConstants.TAG_COMMAND;
import static com.coinflow.monitoring.constant.MetricConstants.TAG_FLUSH_REASON;
import static com.coinflow.monitoring.constant.MetricConstants.TAG_MODULE;
import static com.coinflow.monitoring.constant.MetricConstants.VALUE_MODULE_CONSUMER;
import static com.coinflow.monitoring.constant.MetricConstants.VALUE_NA;

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

    private AtomicReference<Double> backlogGauge;
    private AtomicReference<Double> backlogRetentionRatioGauge;
    private Counter xinfoCounter;
    private final AtomicBoolean retentionWarningActive = new AtomicBoolean(false);

    @PostConstruct
    public void init() {
        this.backlogGauge = metricRecorder.registerGauge(STREAM_BACKLOG_COUNT, 0.0, TAG_MODULE, VALUE_MODULE_CONSUMER);
        this.backlogRetentionRatioGauge = metricRecorder.registerGauge(
                STREAM_BACKLOG_RETENTION_RATIO, 0.0, TAG_MODULE, VALUE_MODULE_CONSUMER);
        this.xinfoCounter = metricRecorder.getCounter(REDIS_COMMAND_COUNT, 
                TAG_COMMAND, "XINFO",
                TAG_FLUSH_REASON, VALUE_NA);
    }

    /**
     * 주기적으로 Lag 수치를 확인합니다. (기본 5초)
     */
    @Scheduled(fixedDelayString = "${monitoring.lag-check-interval-ms:5000}")
    public void monitorLag() {
        String streamKey = properties.streamKey();
        String group = properties.group();

        try {
            // Redis 명령 횟수 기록 (XINFO) - Pre-fetched
            xinfoCounter.increment();

            StreamInfo.XInfoGroups groups = redisTemplate.opsForStream().groups(streamKey);
            if (groups != null) {
                groups.stream()
                        .filter(g -> g.groupName().equals(group))
                        .findFirst()
                        .ifPresent(g -> {
                            Object lagObj = g.getRaw().get("lag");
                            if (lagObj instanceof Number) {
                                double lagValue = ((Number) lagObj).doubleValue();
                                updateBacklogMetrics(lagValue);
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

    void updateBacklogMetrics(double lagValue) {
        double retentionRatio = lagValue / properties.maxLength();
        backlogGauge.set(lagValue);
        backlogRetentionRatioGauge.set(retentionRatio);

        if (retentionRatio >= properties.lagWarningRatio()) {
            if (retentionWarningActive.compareAndSet(false, true)) {
                metricRecorder.increment(
                        STREAM_RETENTION_WARNING_COUNT,
                        TAG_MODULE,
                        VALUE_MODULE_CONSUMER);
                log.error("Redis Stream retention warning. stream={}, group={}, lag={}, maxLength={}, ratio={}",
                        properties.streamKey(), properties.group(), lagValue,
                        properties.maxLength(), retentionRatio);
            }
            return;
        }

        if (retentionWarningActive.compareAndSet(true, false)) {
            log.info("Redis Stream retention recovered. stream={}, group={}, lag={}, maxLength={}, ratio={}",
                    properties.streamKey(), properties.group(), lagValue,
                    properties.maxLength(), retentionRatio);
        } else {
            log.debug("Redis Stream Lag monitored: stream={}, group={}, lag={}, ratio={}",
                    properties.streamKey(), properties.group(), lagValue, retentionRatio);
        }
    }
}
