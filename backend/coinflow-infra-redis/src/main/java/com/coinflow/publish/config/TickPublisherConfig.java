package com.coinflow.publish.config;

import com.coinflow.tick.publisher.TickPublisher;
import com.coinflow.ticker.publisher.TickerPublisher;
import com.coinflow.publish.pubsub.RedisTickerPublisher;
import com.coinflow.publish.stream.RedisStreamTickPublisher;
import com.coinflow.publish.stream.QueuedPipelinedTickPublisher;
import com.coinflow.publish.wal.WalPipelinedTickPublisher;
import com.coinflow.publish.DeliveryStatusProvider;
import com.coinflow.monitoring.MetricRecorder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.util.Assert;

@Configuration
@EnableConfigurationProperties(CollectorDeliveryProperties.class)
public class TickPublisherConfig {

    @Bean
    public RedisStreamTickPublisher redisStreamTickPublisher(
            RedisTemplate<String, byte[]> rawRedisTemplate,
            MetricRecorder metricRecorder,
            @Value("${redis.stream.tick.stream-key:tick:raw}") String streamKey,
            @Value("${redis.stream.tick.max-length:200000}") long maxLength
    ) {
        Assert.hasText(streamKey, "redis.stream.tick.stream-key must not be blank");
        Assert.isTrue(maxLength > 0, "redis.stream.tick.max-length must be greater than zero");
        return new RedisStreamTickPublisher(rawRedisTemplate, metricRecorder, streamKey, maxLength);
    }

    @Bean
    @Primary
    public TickPublisher tickPublisher(
            RedisStreamTickPublisher redisStreamTickPublisher,
            MetricRecorder metricRecorder,
            CollectorDeliveryProperties properties
    ) throws java.io.IOException {
        properties.validate();
        return switch (properties.getMode()) {
            case DIRECT -> redisStreamTickPublisher;
            case PIPELINE -> new QueuedPipelinedTickPublisher(redisStreamTickPublisher, metricRecorder, properties);
            case WAL_PIPELINE -> new WalPipelinedTickPublisher(redisStreamTickPublisher, metricRecorder, properties);
        };
    }

    @Bean
    public HealthIndicator collectorDeliveryHealthIndicator(TickPublisher tickPublisher) {
        return () -> {
            if (!(tickPublisher instanceof DeliveryStatusProvider statusProvider)) {
                return Health.up().withDetail("mode", "DIRECT").build();
            }
            Health.Builder builder = statusProvider.isHealthy() ? Health.up() : Health.down();
            return builder.withDetails(statusProvider.details()).build();
        };
    }

    @Bean
    public TickerPublisher tickerPublisher(
            StringRedisTemplate redisTemplate,
            MetricRecorder metricRecorder,
            @Value("${redis.pubsub.ticker-topic:ticker:broadcast}") String tickerTopic
    ) {
        Assert.hasText(tickerTopic, "redis.pubsub.ticker-topic must not be blank");
        return new RedisTickerPublisher(redisTemplate, metricRecorder, tickerTopic);
    }
}
