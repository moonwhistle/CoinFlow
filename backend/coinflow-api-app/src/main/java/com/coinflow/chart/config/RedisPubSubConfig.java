package com.coinflow.chart.config;

import com.coinflow.chart.service.sync.OhlcWindowSyncService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.data.redis.listener.adapter.MessageListenerAdapter;
import org.springframework.lang.NonNull;

/**
 * Configuration for Redis Pub/Sub to listen for kline events in api-app.
 */
@Configuration
public class RedisPubSubConfig {

    public static final String KLINE_BROADCAST_TOPIC = "kline:broadcast";

    @Bean
    public RedisMessageListenerContainer redisContainer(
            RedisConnectionFactory connectionFactory,
            MessageListenerAdapter listenerAdapter
    ) {
        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(connectionFactory);
        container.addMessageListener(listenerAdapter, new ChannelTopic(KLINE_BROADCAST_TOPIC));
        return container;
    }

    @Bean
    public MessageListenerAdapter listenerAdapter(@NonNull OhlcWindowSyncService syncService) {
        return new MessageListenerAdapter(syncService, "onMessage");
    }
}
