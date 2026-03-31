package com.coinflow.monitoring;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.Callable;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import java.util.concurrent.TimeUnit;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class MetricRecorder {

    private final MeterRegistry meterRegistry;
    
    // (Point 3) OOM 방지를 위한 최대 크기 제한(1000) 및 LRU 정책 적용
    private final Cache<String, Timer> timerCache = Caffeine.newBuilder()
            .maximumSize(1000)
            .build();
            
    private final Cache<String, Counter> counterCache = Caffeine.newBuilder()
            .maximumSize(1000)
            .build();
            
    private final Cache<String, AtomicReference<Double>> gaugeCache = Caffeine.newBuilder()
            .maximumSize(1000)
            .build();

    public Counter getCounter(String metricName, String... tags) {
        String cacheKey = buildCacheKey(metricName, tags);
        return counterCache.get(cacheKey, k -> 
            meterRegistry.counter(metricName, tags)
        );
    }

    public Timer getTimer(String metricName, String... tags) {
        String cacheKey = buildCacheKey(metricName, tags);
        return timerCache.get(cacheKey, k -> 
            Timer.builder(metricName)
                .tags(tags)
                .publishPercentiles(0.5, 0.9, 0.95, 0.99)
                .register(meterRegistry)
        );
    }
    
    /**
     * Gauge를 등록하고 해당 수치를 조절할 수 있는 AtomicReference를 반환합니다.
     */
    public AtomicReference<Double> registerGauge(String metricName, double initialValue, String... tags) {
        String cacheKey = buildCacheKey(metricName, tags);
        return gaugeCache.get(cacheKey, k -> {
            AtomicReference<Double> atomicReference = new AtomicReference<>(initialValue);
            meterRegistry.gauge(metricName, Arrays.asList(convertToTags(tags)), atomicReference, 
                    ref -> ref.get());
            return atomicReference;
        });
    }

    public void increment(String metricName, String... tags) {
        increment(metricName, 1.0, tags);
    }

    public void increment(String metricName, double amount, String... tags) {
        getCounter(metricName, tags).increment(amount);
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
        registerGauge(metricName, value, tags).set(value);
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


    private String buildCacheKey(String metricName, String[] tags) {
        if (tags.length == 0) return metricName;
        StringBuilder sb = new StringBuilder(metricName);
        for (String tag : tags) {
            sb.append(":").append(tag);
        }
        return sb.toString();
    }
}
