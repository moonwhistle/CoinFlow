package com.coinflow.recovery.service;

import com.coinflow.domain.recovery.repository.ConsumerCheckpointRepository;
import com.coinflow.domain.recovery.repository.FailedRecordRepository;
import com.coinflow.tick.serialization.TickRawBinaryCodec;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(properties = {"redis.stream.tick.enabled=true", "coinflow.recovery.maintenance.enabled=true"})
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@EnabledIfEnvironmentVariable(named = "COINFLOW_TEST_REDIS_PORT", matches = "\\d+")
class ConsumerStartupIntegrationTest {
    private static final String STREAM = "test:startup:" + UUID.randomUUID();
    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.port", () -> System.getenv("COINFLOW_TEST_REDIS_PORT"));
        registry.add("redis.stream.tick.stream-key", () -> STREAM);
        registry.add("spring.datasource.url", () -> "jdbc:h2:mem:startup;MODE=PostgreSQL;DB_CLOSE_DELAY=-1");
    }
    @Autowired RedisTemplate<String, byte[]> rawRedisTemplate;
    @Autowired ConsumerCheckpointRepository checkpoints;
    @Autowired FailedRecordRepository failures;
    @Autowired org.springframework.data.redis.stream.StreamMessageListenerContainer<?, ?> tickStreamContainer;

    @Test void actualListenerCommitsBeforeAckAndRoutesMalformedRecordToDlq() {
        try {
            var invalid = rawRedisTemplate.opsForStream().add(STREAM, Map.of("p", new byte[] {127}));
            await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
                assertEquals(1, failures.count());
                assertNotNull(failures.findAll().get(0).getDlqId());
                assertEquals(invalid.getValue(), checkpoints.findById(1L).orElseThrow().getRecordId());
                assertEquals(0, rawRedisTemplate.opsForStream().pending(STREAM, "test-group").getTotalPendingMessages());
            });
            byte[] payload = TickRawBinaryCodec.encode("btcusdt", BigDecimal.TEN, BigDecimal.ONE, 60_000);
            var valid = rawRedisTemplate.opsForStream().add(STREAM, Map.of("p", payload));
            await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
                assertEquals(valid.getValue(), checkpoints.findById(1L).orElseThrow().getRecordId());
                assertEquals(0, rawRedisTemplate.opsForStream().pending(STREAM, "test-group").getTotalPendingMessages());
            });
        } finally {
            tickStreamContainer.stop();
            rawRedisTemplate.delete(List.of(STREAM, STREAM + ":dlq", STREAM + ":dlq:index"));
        }
    }
}
