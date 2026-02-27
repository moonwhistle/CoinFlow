package com.coinflow.ws.config;

import com.coinflow.ws.service.TickRawStreamConsumer;
import java.time.Duration;
import java.net.InetAddress;
import java.net.UnknownHostException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.stream.StreamMessageListenerContainer;
import org.springframework.data.redis.stream.Subscription;

@Slf4j
@Configuration
@RequiredArgsConstructor
public class RedisStreamConfig {

        private static final String TICK_STREAM_KEY = "tick:raw";
        private static final String CONSUMER_GROUP = "ws-gateway-group";

        @Bean
        public Subscription subscription(RedisConnectionFactory factory, TickRawStreamConsumer streamListener)
                        throws UnknownHostException {
                // Create Consumer Group (if not exists) logic is assumed to be handled
                // externally or via init script
                // For simplicity, we assume the group exists or we catch the error in a real
                // scenario
                // To make it robust: You might want a @PostConstruct to create group if
                // missing.

                StreamMessageListenerContainer.StreamMessageListenerContainerOptions<String, MapRecord<String, String, String>> options = StreamMessageListenerContainer.StreamMessageListenerContainerOptions
                                .builder()
                                .pollTimeout(Duration.ofMillis(100))
                                .build();

                StreamMessageListenerContainer<String, MapRecord<String, String, String>> container = StreamMessageListenerContainer
                                .create(factory, options);

                // Create Consumer Group if not exists
                try {
                        factory.getConnection().streamCommands()
                                        .xGroupCreate(TICK_STREAM_KEY.getBytes(), CONSUMER_GROUP,
                                                        ReadOffset.from("0-0"), true);
                } catch (Exception e) {
                        // Check if group already exists (NOGROUP or BUSYGROUP) or other error
                        // BUSYGROUP Consumer Group name already exists - Ignore
                        // If error is unrelated to existence, it might be an issue, but usually safe to
                        // proceed or log
                        log.debug("Consumer group exists or failed to create: {}", e.getMessage());
                }

                // Unique Consumer Name: Hostname + Random or UUID (to allow multiple gateway
                // instances)
                String consumerName = InetAddress.getLocalHost().getHostName() + "-" + System.currentTimeMillis();

                Subscription subscription = container.receive(
                                Consumer.from(CONSUMER_GROUP, consumerName),
                                StreamOffset.create(TICK_STREAM_KEY, ReadOffset.lastConsumed()),
                                streamListener);

                container.start();

                return subscription;
        }
}
