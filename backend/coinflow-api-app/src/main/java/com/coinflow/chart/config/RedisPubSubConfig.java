package com.coinflow.chart.config;

import com.coinflow.chart.service.sync.OhlcWindowSyncService;
import com.coinflow.aggregation.infrastructure.redis.RedisKlineBroadcaster;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.data.redis.listener.adapter.MessageListenerAdapter;

/**
 * Configuration for Redis Pub/Sub to listen for kline events in api-app.
 */
@Configuration
public class RedisPubSubConfig {

    @Bean
    public RedisMessageListenerContainer redisContainer(
            RedisConnectionFactory connectionFactory,
            MessageListenerAdapter listenerAdapter
    ) {
        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(connectionFactory);
        container.addMessageListener(listenerAdapter, new ChannelTopic(RedisKlineBroadcaster.KLINE_BROADCAST_TOPIC));
        return container;
    }

    @Bean
    public MessageListenerAdapter listenerAdapter(OhlcWindowSyncService syncService) {
        // "onMessage" is the default method if not specified, but we'll be explicit or use the service directly.
        return new MessageListenerAdapter(syncService, "onMessage");
    }
}
