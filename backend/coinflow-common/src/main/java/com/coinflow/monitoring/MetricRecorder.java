package com.coinflow.monitoring;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class MetricRecorder {

    private final MeterRegistry meterRegistry;
    private final Map<String, Timer> timerCache = new ConcurrentHashMap<>();
    private final Map<String, Counter> counterCache = new ConcurrentHashMap<>();
    private final Map<String, AtomicReference<Double>> gaugeCache = new ConcurrentHashMap<>();

    public void increment(String metricName, String... tags) {
        String cacheKey = buildCacheKey(metricName, tags);
        counterCache.computeIfAbsent(cacheKey, k -> 
            meterRegistry.counter(metricName, tags)
        ).increment();
    }

    public void recordTime(String metricName, Runnable runnable, String... tags) {
        getTimer(metricName, tags).record(runnable);
    }

    public <T> T recordTime(String metricName, Callable<T> callable, String... tags) {
        Timer timer = getTimer(metricName, tags);
        try {
            return timer.recordCallable(callable);
        } catch (Exception e) {
            throw (e instanceof RuntimeException) ? (RuntimeException) e : new RuntimeException(e);
        }
    }

    public void recordTime(String metricName, long millis, String... tags) {
        getTimer(metricName, tags).record(millis, TimeUnit.MILLISECONDS);
    }

    public void recordTimeNanos(String metricName, long nanos, String... tags) {
        getTimer(metricName, tags).record(nanos, TimeUnit.NANOSECONDS);
    }

    /**
     * 실시간 수치(Gauge)를 기록합니다.
     */
    public void recordValue(String metricName, double value, String... tags) {
        String cacheKey = buildCacheKey(metricName, tags);
        gaugeCache.computeIfAbsent(cacheKey, k -> {
            AtomicReference<Double> atomicReference = new AtomicReference<>(value);
            meterRegistry.gauge(metricName, Arrays.asList(convertToTags(tags)), atomicReference, 
                    ref -> ref.get());
            return atomicReference;
        }).set(value);
    }

    private io.micrometer.core.instrument.Tag[] convertToTags(String[] tags) {
        if (tags == null || tags.length < 2) return new io.micrometer.core.instrument.Tag[0];
        int tagCount = tags.length / 2;
        io.micrometer.core.instrument.Tag[] micrometerTags = new io.micrometer.core.instrument.Tag[tagCount];
        for (int i = 0; i < tagCount; i++) {
            micrometerTags[i] = io.micrometer.core.instrument.Tag.of(tags[2 * i], tags[2 * i + 1]);
        }
        return micrometerTags;
    }

    private Timer getTimer(String metricName, String[] tags) {
        String cacheKey = buildCacheKey(metricName, tags);
        return timerCache.computeIfAbsent(cacheKey, k -> 
            Timer.builder(metricName)
                .tags(tags)
                .publishPercentiles(0.5, 0.9, 0.95, 0.99)
                .register(meterRegistry)
        );
    }

    private String buildCacheKey(String metricName, String[] tags) {
        if (tags.length == 0) return metricName;
        StringBuilder sb = new StringBuilder(metricName);
        for (String tag : tags) {
            sb.append(":").append(tag);
        }
        return sb.toString();
    }
}
