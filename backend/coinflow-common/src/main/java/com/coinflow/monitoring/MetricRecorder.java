package com.coinflow.monitoring;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.util.concurrent.Callable;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class MetricRecorder {

    private final MeterRegistry meterRegistry;

    /**
     * 단일 카운터를 1 증가시킵니다.
     * @param metricName MetricConstants의 메트릭명
     * @param tags (선택) 키-값 형태의 태그 목록 (예: "type", "success")
     */
    public void increment(String metricName, String... tags) {
        meterRegistry.counter(metricName, tags).increment();
    }
    
    /**
     * 반환값이 없는 메서드의 수행 시간과 백분위수(P95, P99 등)를 함께 측정합니다.
     */
    public void recordTime(String metricName, Runnable runnable, String... tags) {
        Timer timer = Timer.builder(metricName)
                .tags(tags)
                .publishPercentiles(0.5, 0.9, 0.95, 0.99) // P50, P90, P95, P99 지표 전송 자동화
                .register(meterRegistry);
        timer.record(runnable);
    }

    /**
     * 반환값이 있는 메서드의 수행 시간과 백분위수(P95, P99 등)를 함께 측정하고 결과를 반환합니다.
     */
    public <T> T recordTime(String metricName, Callable<T> callable, String... tags) {
        Timer timer = Timer.builder(metricName)
                .tags(tags)
                .publishPercentiles(0.5, 0.9, 0.95, 0.99)
                .register(meterRegistry);
        try {
            return timer.recordCallable(callable);
        } catch (Exception e) {
            if (e instanceof RuntimeException) {
                throw (RuntimeException) e;
            }
            throw new RuntimeException("Metric evaluation failed", e);
        }
    }

    /**
     * 이미 계산된 밀리초(ms) 단위의 기간을 기록합니다.
     */
    public void recordTime(String metricName, long millis, String... tags) {
        Timer timer = Timer.builder(metricName)
                .tags(tags)
                .publishPercentiles(0.5, 0.9, 0.95, 0.99)
                .register(meterRegistry);
        timer.record(java.time.Duration.ofMillis(millis));
    }

    /**
     * 이미 계산된 나노초(ns) 단위의 기간을 기록합니다.
     */
    public void recordTimeNanos(String metricName, long nanos, String... tags) {
        Timer timer = Timer.builder(metricName)
                .tags(tags)
                .publishPercentiles(0.5, 0.9, 0.95, 0.99)
                .register(meterRegistry);
        timer.record(java.time.Duration.ofNanos(nanos));
    }
}
