package com.coinflow.publish.config;

import com.coinflow.tick.publisher.TickPublisher;
import com.coinflow.publish.stream.RedisStreamTickPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.RedisTemplate;

@Configuration
public class TickPublisherConfig {

    @Bean
    public TickPublisher tickPublisher(
            RedisTemplate<String, String> redisTemplate
    ) {
        return new RedisStreamTickPublisher(redisTemplate);
    }
}
