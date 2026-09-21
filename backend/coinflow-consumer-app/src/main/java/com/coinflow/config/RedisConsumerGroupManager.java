package com.coinflow.config;

import com.coinflow.domain.recovery.service.RecoveryLedger;
import com.coinflow.config.properties.TickConsumerProperties;
import java.nio.charset.StandardCharsets;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class RedisConsumerGroupManager {

    private static final String ERROR_BUSY_GROUP = "BUSYGROUP";
    private static final String ERROR_NO_GROUP = "NOGROUP";

    private final RedisTemplate<String, String> redisTemplate;
    private final TickConsumerProperties properties;
    private final ConsumerApplicationShutdown applicationShutdown;
    private final RecoveryLedger ledger;

    public void ensureConsumerGroup() {
        String offset = ledger.locked(checkpoint -> checkpoint.getRecordId());
        try {
            redisTemplate.execute((RedisCallback<String>) connection ->
                    connection.streamCommands().xGroupCreate(
                            raw(properties.streamKey()),
                            properties.group(),
                            ReadOffset.from(offset),
                            true));
            log.info("Created Redis consumer group. stream={}, group={}",
                    properties.streamKey(), properties.group());
        } catch (RuntimeException e) {
            if (containsError(e, ERROR_BUSY_GROUP)) {
                log.info("Redis consumer group already exists. stream={}, group={}",
                        properties.streamKey(), properties.group());
                return;
            }
            throw new IllegalStateException(
                    "Failed to initialize Redis consumer group. stream=" + properties.streamKey()
                            + ", group=" + properties.group(),
                    e);
        }
    }

    public void handleSubscriptionError(Throwable error) {
        log.error("Redis subscription failed; restart through checkpoint recovery is required", error);
        applicationShutdown.request();
    }

    public boolean shouldCancelSubscription(Throwable error) {
        return true;
    }

    boolean isNoGroup(Throwable error) {
        return containsError(error, ERROR_NO_GROUP);
    }

    private static boolean containsError(Throwable error, String code) {
        Throwable current = error;
        while (current != null) {
            String message = current.getMessage();
            if (message != null && message.contains(code)) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private static byte[] raw(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }
}
