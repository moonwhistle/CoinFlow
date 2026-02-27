package com.coinflow.ws.config;

import com.coinflow.ws.service.KlineBroadcastConsumer;
import com.coinflow.ws.service.TickerBroadcastConsumer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

@Slf4j
@Configuration
@RequiredArgsConstructor
public class RedisStreamConfig {

        @Bean
        public RedisMessageListenerContainer redisMessageListenerContainer(
                        RedisConnectionFactory connectionFactory,
                        KlineBroadcastConsumer klineBroadcastConsumer,
                        TickerBroadcastConsumer tickerBroadcastConsumer) {

                RedisMessageListenerContainer container = new RedisMessageListenerContainer();
                container.setConnectionFactory(connectionFactory);
                container.addMessageListener(klineBroadcastConsumer, new ChannelTopic("kline:broadcast"));
                container.addMessageListener(tickerBroadcastConsumer, new ChannelTopic("ticker:broadcast"));
                return container;
        }
}
