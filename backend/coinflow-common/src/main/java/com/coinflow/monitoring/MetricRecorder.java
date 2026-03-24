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
     * 반환값이 없는 메서드의 수행 시간을 측정합니다.
     */
    public void recordTime(String metricName, Runnable runnable, String... tags) {
        Timer timer = meterRegistry.timer(metricName, tags);
        timer.record(runnable);
    }

    /**
     * 반환값이 있는 메서드의 수행 시간을 측정하고 결과를 반환합니다.
     */
    public <T> T recordTime(String metricName, Callable<T> callable, String... tags) {
        Timer timer = meterRegistry.timer(metricName, tags);
        try {
            return timer.recordCallable(callable);
        } catch (Exception e) {
            if (e instanceof RuntimeException) {
                throw (RuntimeException) e;
            }
            throw new RuntimeException("Metric evaluation failed", e);
        }
    }
}
