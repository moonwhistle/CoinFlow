package com.coinflow.config;

import com.coinflow.config.properties.TickConsumerProperties;
import com.coinflow.consumer.TickRawEventConsumer;
import jakarta.annotation.PreDestroy;
import java.time.Duration;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.serializer.RedisSerializer;
import org.springframework.data.redis.stream.StreamMessageListenerContainer;
import org.springframework.data.redis.stream.StreamMessageListenerContainer.StreamReadRequest;
import org.springframework.data.redis.stream.StreamMessageListenerContainer.StreamMessageListenerContainerOptions;

/**
 * Redis Stream 소비자(Consumer) 설정을 담당하며, 바이너리 수신(Phase 3.1)을 지원합니다.
 */
@Slf4j
@Configuration
@EnableConfigurationProperties(TickConsumerProperties.class)
@RequiredArgsConstructor
public class RedisConsumerConfig {

    private final RedisConnectionFactory connectionFactory;
    private final TickRawEventConsumer consumer;
    private final TickConsumerProperties properties;
    private final RedisConsumerGroupManager consumerGroupManager;

    private StreamMessageListenerContainer<String, MapRecord<String, String, byte[]>> container;

    @Bean
    @ConditionalOnProperty(prefix = "redis.stream.tick", name = "enabled", havingValue = "true", matchIfMissing = true)
    public StreamMessageListenerContainer<String, MapRecord<String, String, byte[]>> tickStreamContainer() {
        consumerGroupManager.ensureConsumerGroup();

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
        StreamReadRequest<String> readRequest = StreamReadRequest
                .builder(StreamOffset.create(properties.streamKey(), ReadOffset.lastConsumed()))
                .consumer(Consumer.from(properties.group(), properties.consumerName()))
                .errorHandler(consumerGroupManager::handleSubscriptionError)
                .cancelOnError(consumerGroupManager::shouldCancelSubscription)
                .build();
        container.register(readRequest, consumer);
        container.start();

        log.info("Successfully started Redis Stream Container (Binary Mode) for group: {}", properties.group());
        return container;
    }

    @PreDestroy
    public void shutdown() {
        if (container != null && container.isRunning()) {
            container.stop();
            log.info("Redis Stream Container stopped.");
        }
    }
}
