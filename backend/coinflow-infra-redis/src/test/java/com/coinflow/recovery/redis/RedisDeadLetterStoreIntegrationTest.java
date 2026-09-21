package com.coinflow.recovery.redis;

import com.coinflow.domain.recovery.domain.FailedRecord;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.connection.stream.*;
import org.springframework.data.redis.core.StringRedisTemplate;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfEnvironmentVariable(named = "COINFLOW_TEST_REDIS_PORT", matches = "\\d+")
class RedisDeadLetterStoreIntegrationTest {
    private LettuceConnectionFactory connection;
    private StringRedisTemplate redis;
    private RedisDeadLetterStore store;
    private String stream;
    private FailedRecord failure;

    @BeforeEach void setup() {
        connection = new LettuceConnectionFactory("localhost", Integer.parseInt(System.getenv("COINFLOW_TEST_REDIS_PORT")));
        connection.afterPropertiesSet();
        redis = new StringRedisTemplate(connection);
        stream = "test:dlq:" + UUID.randomUUID();
        RecordId id = redis.opsForStream().add(stream, Map.of("p", "payload"));
        redis.opsForStream().createGroup(stream, ReadOffset.from("0-0"), "group");
        redis.opsForStream().read(Consumer.from("group", "dead-consumer"), StreamReadOptions.empty().count(1),
                StreamOffset.create(stream, ReadOffset.lastConsumed()));
        failure = new FailedRecord();
        failure.setId(FailedRecord.key(stream, "group", id.getValue()));
        failure.setStreamKey(stream); failure.setConsumerGroup("group"); failure.setRecordId(id.getValue());
        failure.setPayload("cGF5bG9hZA=="); failure.setReason("test");
        store = new RedisDeadLetterStore(redis);
    }

    @AfterEach void cleanup() {
        redis.delete(List.of(stream, stream + ":dlq", stream + ":dlq:index"));
        connection.destroy();
    }

    @Test void publishesAndRemovesPelButKeepsOriginalAndIsIdempotent() {
        String first = store.publishAndAcknowledge(failure);
        assertEquals(first, store.publishAndAcknowledge(failure));
        assertEquals(0, redis.opsForStream().pending(stream, "group").getTotalPendingMessages());
        assertEquals(1, redis.opsForStream().size(stream));
        assertEquals(1, redis.opsForStream().size(stream + ":dlq"));
    }

    @Test void wrongDlqTypeNeverAcknowledgesOriginal() {
        redis.opsForValue().set(stream + ":dlq", "wrong-type");
        assertThrows(RuntimeException.class, () -> store.publishAndAcknowledge(failure));
        assertEquals(1, redis.opsForStream().pending(stream, "group").getTotalPendingMessages());
    }

    @Test void wrongIndexTypeDoesNotPartiallyPublishOrAck() {
        redis.opsForValue().set(stream + ":dlq:index", "wrong-type");
        assertThrows(RuntimeException.class, () -> store.publishAndAcknowledge(failure));
        assertFalse(redis.hasKey(stream + ":dlq"));
        assertEquals(1, redis.opsForStream().pending(stream, "group").getTotalPendingMessages());
    }
}
