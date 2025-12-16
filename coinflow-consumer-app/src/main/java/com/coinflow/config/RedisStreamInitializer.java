package com.coinflow.config;

import com.coinflow.config.properties.TickConsumerProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class RedisStreamInitializer implements ApplicationRunner {

    private final RedisTemplate<String, String> redisTemplate;
    private final TickConsumerProperties properties;

    @Override
    public void run(ApplicationArguments args) {
        try {
            redisTemplate.opsForStream().createGroup(
                    properties.streamKey(),
                    ReadOffset.latest(),
                    properties.group()
            );
        } catch (Exception e) {
            log.info("Already Exist Redis Consumer Group");
        }
    }
}
