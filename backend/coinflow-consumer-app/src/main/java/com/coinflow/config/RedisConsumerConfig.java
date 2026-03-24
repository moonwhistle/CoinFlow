package com.coinflow.config;

import com.coinflow.config.properties.TickConsumerProperties;
import com.coinflow.consumer.TickRawEventConsumer;
import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.RedisSystemException;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.stream.StreamMessageListenerContainer;
import org.springframework.data.redis.stream.StreamMessageListenerContainer.StreamMessageListenerContainerOptions;

@Slf4j
@Configuration
@EnableConfigurationProperties(TickConsumerProperties.class)
@RequiredArgsConstructor
public class RedisConsumerConfig {

    private static final String ERROR_BUSYGROUP = "BUSYGROUP";
    private static final String ERROR_NO_SUCH_KEY = "No such key";
    private static final String ERROR_NO_GROUP = "NOGROUP";
    private static final String DUMMY_EVENT_KEY = "init-event";
    private static final String DUMMY_EVENT_VALUE = "true";

    private final RedisConnectionFactory connectionFactory;
    private final TickRawEventConsumer consumer;
    private final TickConsumerProperties properties;

    private StreamMessageListenerContainer<String, MapRecord<String, String, String>> container;

    @Bean
    public StreamMessageListenerContainer<String, MapRecord<String, String, String>> tickStreamContainer(
            RedisTemplate<String, String> redisTemplate) {

        initializeConsumerGroup(redisTemplate);

        StreamMessageListenerContainerOptions<String, MapRecord<String, String, String>> options =
                StreamMessageListenerContainerOptions.builder()
                        .pollTimeout(Duration.ofSeconds(2))
                        .build();

        container = StreamMessageListenerContainer.create(connectionFactory, options);
        container.receive(
                Consumer.from(properties.group(), properties.consumerName()),
                StreamOffset.create(properties.streamKey(), ReadOffset.lastConsumed()),
                this.consumer
        );
        container.start();

        return container;
    }

    private void initializeConsumerGroup(RedisTemplate<String, String> redisTemplate) {
        try {
            redisTemplate.opsForStream().createGroup(properties.streamKey(), ReadOffset.latest(), properties.group());
            log.info("Successfully created Redis Consumer Group: {}", properties.group());
        } catch (RedisSystemException e) {
            String msg = e.getCause() != null ? e.getCause().getMessage() : "";
            if (msg.contains(ERROR_BUSYGROUP)) {
                log.info("Redis consumer group already exists: {}", properties.group());
            } else if (msg.contains(ERROR_NO_SUCH_KEY) || msg.contains(ERROR_NO_GROUP)) {
                log.warn("Redis stream does not exist. Creating stream and consumer group manually...");
                redisTemplate.opsForStream().add(properties.streamKey(), Map.of(DUMMY_EVENT_KEY, DUMMY_EVENT_VALUE));
                redisTemplate.opsForStream().createGroup(properties.streamKey(), ReadOffset.latest(), properties.group());
                log.info("Successfully created Stream and Consumer Group.");
            } else {
                log.error("Failed to initialize Redis Consumer Group", e);
                throw e;
            }
        }
    }

    @PreDestroy
    public void shutdown() {
        if (container != null && container.isRunning()) {
            container.stop();
        }
    }
}
