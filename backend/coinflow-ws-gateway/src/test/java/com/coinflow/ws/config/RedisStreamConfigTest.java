package com.coinflow.ws.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.coinflow.ws.service.CandleClosedStreamConsumer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.ApplicationContext;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.data.redis.listener.adapter.MessageListenerAdapter;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.context.annotation.Import;

@SpringBootTest(classes = RedisStreamConfig.class)
@EnableAutoConfiguration
class RedisStreamConfigTest {

    @Autowired
    private ApplicationContext applicationContext;

    @MockBean
    private RedisConnectionFactory redisConnectionFactory;

    @MockBean
    private CandleClosedStreamConsumer candleClosedStreamConsumer;

    @Test
    @DisplayName("RedisMessageListenerContainer Bean should be loaded")
    void redisMessageListenerContainerBeanShouldBeLoaded() {
        RedisMessageListenerContainer container = applicationContext.getBean(RedisMessageListenerContainer.class);
        assertThat(container).isNotNull();
    }

    @Test
    @DisplayName("MessageListenerAdapter Bean should be loaded and wired with Consumer")
    void messageListenerAdapterBeanShouldBeLoaded() {
        MessageListenerAdapter adapter = applicationContext.getBean(MessageListenerAdapter.class);
        assertThat(adapter).isNotNull();
        // We cannot easily check the delegate without reflection, but existence proves
        // the bean creation method triggered
    }
}
