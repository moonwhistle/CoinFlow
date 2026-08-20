package com.coinflow.publish.config;

import com.coinflow.tick.publisher.TickPublisher;
import com.coinflow.publish.stream.RedisStreamTickPublisher;
import com.coinflow.monitoring.MetricRecorder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.util.Assert;

@Configuration
public class TickPublisherConfig {

    @Bean
    public TickPublisher tickPublisher(
            RedisTemplate<String, byte[]> rawRedisTemplate,
            MetricRecorder metricRecorder,
            @Value("${redis.stream.tick.stream-key:tick:raw}") String streamKey,
            @Value("${redis.stream.tick.max-length:200000}") long maxLength
    ) {
        Assert.hasText(streamKey, "redis.stream.tick.stream-key must not be blank");
        Assert.isTrue(maxLength > 0, "redis.stream.tick.max-length must be greater than zero");
        return new RedisStreamTickPublisher(rawRedisTemplate, metricRecorder, streamKey, maxLength);
    }
}
