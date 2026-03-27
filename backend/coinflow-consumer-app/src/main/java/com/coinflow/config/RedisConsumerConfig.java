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
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.RedisSerializer;
import org.springframework.data.redis.stream.StreamMessageListenerContainer;
import org.springframework.data.redis.stream.StreamMessageListenerContainer.StreamMessageListenerContainerOptions;

/**
 * Redis Stream 소비자(Consumer) 설정을 담당하며, 바이너리 수신(Phase 3.1)을 지원합니다.
 */
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

    private StreamMessageListenerContainer<String, MapRecord<String, String, byte[]>> container;

    @Bean
    public StreamMessageListenerContainer<String, MapRecord<String, String, byte[]>> tickStreamContainer(
            RedisTemplate<String, String> redisTemplate) {

        initializeConsumerGroup(redisTemplate);

        // 바이너리 수신을 위한 컨테이너 옵션 설정 (ByteArrayRedisSerializer)
        @SuppressWarnings("unchecked")
        StreamMessageListenerContainerOptions<String, MapRecord<String, String, byte[]>> options = 
                (StreamMessageListenerContainerOptions<String, MapRecord<String, String, byte[]>>) (Object) 
                StreamMessageListenerContainerOptions.builder()
                        .keySerializer(RedisSerializer.string())
                        .hashKeySerializer(RedisSerializer.string())
                        .hashValueSerializer(RedisSerializer.byteArray())
                        .pollTimeout(Duration.ofSeconds(2))
                        .build();

        container = StreamMessageListenerContainer.create(connectionFactory, options);
        container.receive(
                Consumer.from(properties.group(), properties.consumerName()),
                StreamOffset.create(properties.streamKey(), ReadOffset.lastConsumed()),
                this.consumer);
        container.start();

        log.info("Successfully started Redis Stream Container (Binary Mode) for group: {}", properties.group());
        return container;
    }

    private void initializeConsumerGroup(RedisTemplate<String, String> redisTemplate) {
        String streamKey = properties.streamKey();
        String group = properties.group();
        
        try {
            redisTemplate.opsForStream().createGroup(streamKey, ReadOffset.latest(), group);
        } catch (Exception e) {
            String msg = e.getMessage() != null ? e.getMessage() : "";
            if (msg.contains(ERROR_BUSYGROUP)) {
                log.info("Redis consumer group already exists: {}", group);
            } else if (msg.contains(ERROR_NO_SUCH_KEY) || msg.contains(ERROR_NO_GROUP)) {
                log.warn("Redis stream does not exist. Initializing stream and group: {}", group);
                redisTemplate.opsForStream().add(streamKey, Map.of(DUMMY_EVENT_KEY, DUMMY_EVENT_VALUE));
                redisTemplate.opsForStream().createGroup(streamKey, ReadOffset.latest(), group);
            } else {
                log.error("Critical error during Redis Consumer Group initialization: {}", msg);
            }
        }
    }

    @PreDestroy
    public void shutdown() {
        if (container != null && container.isRunning()) {
            container.stop();
            log.info("Redis Stream Container stopped.");
        }
    }
}
