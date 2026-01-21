package com.coinflow.ws.config;

import com.coinflow.tick.event.TickRawEvent;
import com.coinflow.ws.service.TickRawStreamConsumer;
import java.time.Duration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.stream.Streamlistener;
import org.springframework.data.redis.stream.StreamMessageListenerContainer;
import org.springframework.data.redis.stream.Subscription;

@Configuration
public class RedisStreamConfig {

    private static final String STREAM_KEY = "tick:raw";
    private static final String CONSUMER_GROUP = "ws-gateway-group";
    private static final String CONSUMER_NAME = "ws-gateway-instance-1"; // TODO: Should be unique per instance

    @Bean
    public Subscription subscription(RedisConnectionFactory factory, TickRawStreamConsumer streamListener) {
        StreamMessageListenerContainer.StreamMessageListenerContainerOptions<String, MapRecord<String, String, String>> options = StreamMessageListenerContainer.StreamMessageListenerContainerOptions
                .builder()
                .pollTimeout(Duration.ofMillis(100))
                .build();

        StreamMessageListenerContainer<String, MapRecord<String, String, String>> container = StreamMessageListenerContainer
                .create(factory, options);

        // Auto-create group if possible or assume it exists (Coinflow-infra-redis might
        // handle this)
        // For simplicity, we assume the stream exists.

        Subscription subscription = container.receive(
                Consumer.from(CONSUMER_GROUP, CONSUMER_NAME),
                StreamOffset.create(STREAM_KEY, ReadOffset.lastConsumed()),
                streamListener);

        container.start();
        return subscription;
    }
}
