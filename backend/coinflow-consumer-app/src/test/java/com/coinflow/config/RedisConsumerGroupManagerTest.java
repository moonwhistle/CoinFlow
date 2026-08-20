package com.coinflow.config;

import com.coinflow.config.properties.TickConsumerProperties;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisStreamCommands;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.RedisTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RedisConsumerGroupManagerTest {

    @Mock
    private RedisTemplate<String, String> redisTemplate;

    @Mock
    private RedisConnection connection;

    @Mock
    private RedisStreamCommands streamCommands;

    @Mock
    private ConsumerApplicationShutdown applicationShutdown;

    private RedisConsumerGroupManager groupManager;

    @BeforeEach
    void setUp() {
        TickConsumerProperties properties = new TickConsumerProperties(
                "tick:raw", "tick-consumer-group", "consumer-1", 200_000L, 0.8);
        groupManager = new RedisConsumerGroupManager(redisTemplate, properties, applicationShutdown);
    }

    @Test
    void createsGroupAndStreamAtomically() {
        stubRedisExecute();

        groupManager.ensureConsumerGroup();

        ArgumentCaptor<byte[]> streamKey = ArgumentCaptor.forClass(byte[].class);
        ArgumentCaptor<ReadOffset> readOffset = ArgumentCaptor.forClass(ReadOffset.class);
        verify(streamCommands).xGroupCreate(
                streamKey.capture(),
                eq("tick-consumer-group"),
                readOffset.capture(),
                eq(true));

        assertThat(new String(streamKey.getValue(), StandardCharsets.UTF_8)).isEqualTo("tick:raw");
        assertThat(readOffset.getValue().getOffset()).isEqualTo("$");
    }

    @Test
    void acceptsExistingConsumerGroup() {
        stubRedisExecute();
        when(streamCommands.xGroupCreate(any(byte[].class), any(), any(ReadOffset.class), eq(true)))
                .thenThrow(new RuntimeException("BUSYGROUP Consumer Group name already exists"));

        assertThatCode(groupManager::ensureConsumerGroup).doesNotThrowAnyException();
    }

    @Test
    void failsStartupForUnexpectedGroupInitializationError() {
        stubRedisExecute();
        when(streamCommands.xGroupCreate(any(byte[].class), any(), any(ReadOffset.class), eq(true)))
                .thenThrow(new RuntimeException("Redis connection failed"));

        assertThatThrownBy(groupManager::ensureConsumerGroup)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Failed to initialize Redis consumer group");
    }

    @Test
    void keepsSubscriptionActiveForNestedNoGroupError() {
        RuntimeException error = new RuntimeException(
                "Error in execution", new RuntimeException("NOGROUP No such consumer group"));

        assertThat(groupManager.shouldCancelSubscription(error)).isFalse();
        assertThat(groupManager.shouldCancelSubscription(new RuntimeException("timeout"))).isTrue();
    }

    @Test
    void recreatesMissingGroupWithoutCancellingSubscription() {
        stubRedisExecute();
        RuntimeException error = new RuntimeException("NOGROUP No such consumer group");

        groupManager.handleSubscriptionError(error);

        verify(streamCommands).xGroupCreate(any(byte[].class), eq("tick-consumer-group"), any(ReadOffset.class), eq(true));
    }

    @Test
    void shutsDownApplicationForFatalSubscriptionError() {
        RuntimeException error = new RuntimeException("Redis command timeout");

        groupManager.handleSubscriptionError(error);

        verify(applicationShutdown).request();
    }

    @SuppressWarnings("unchecked")
    private void stubRedisExecute() {
        when(connection.streamCommands()).thenReturn(streamCommands);
        doAnswer(invocation -> {
            RedisCallback<Object> callback = invocation.getArgument(0);
            return callback.doInRedis(connection);
        }).when(redisTemplate).execute(any(RedisCallback.class));
    }
}
