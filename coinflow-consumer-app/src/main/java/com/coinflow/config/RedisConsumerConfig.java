package com.coinflow.config;

import com.coinflow.config.properties.TickConsumerProperties;
import com.coinflow.consumer.TickRawEventConsumer;
import java.time.Duration;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.stream.StreamMessageListenerContainer;
import org.springframework.data.redis.stream.StreamMessageListenerContainer.StreamMessageListenerContainerOptions;

@Configuration
@EnableConfigurationProperties(TickConsumerProperties.class)
@RequiredArgsConstructor
public class RedisConsumerConfig {

    private final RedisConnectionFactory connectionFactory;
    private final TickRawEventConsumer consumer;
    private final TickConsumerProperties properties;

    @Bean
    public StreamMessageListenerContainer<String, MapRecord<String, String, String>> tickStreamContainer() {
        StreamMessageListenerContainerOptions<String, MapRecord<String, String, String>> options =
                StreamMessageListenerContainerOptions.builder()
                        .pollTimeout(Duration.ofSeconds(2))
                        .build();

        StreamMessageListenerContainer<String, MapRecord<String, String, String>> container =
                StreamMessageListenerContainer.create(connectionFactory, options);

        container.receive(
                Consumer.from(properties.group(), properties.consumerName()),
                StreamOffset.create(properties.streamKey(), ReadOffset.lastConsumed()),
                consumer
        );
        container.start();

        return container;
    }
}
