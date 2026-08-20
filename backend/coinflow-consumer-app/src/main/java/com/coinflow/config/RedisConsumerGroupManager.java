package com.coinflow.config;

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

    public void ensureConsumerGroup() {
        try {
            redisTemplate.execute((RedisCallback<String>) connection ->
                    connection.streamCommands().xGroupCreate(
                            raw(properties.streamKey()),
                            properties.group(),
                            ReadOffset.latest(),
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
        if (!isNoGroup(error)) {
            log.error("Redis Stream subscription failed. stream={}, group={}",
                    properties.streamKey(), properties.group(), error);
            return;
        }

        log.warn("Redis consumer group is missing. Recreating group without cancelling subscription. "
                        + "stream={}, group={}",
                properties.streamKey(), properties.group());
        try {
            ensureConsumerGroup();
        } catch (RuntimeException recoveryError) {
            log.error("Failed to recover missing Redis consumer group. stream={}, group={}",
                    properties.streamKey(), properties.group(), recoveryError);
        }
    }

    public boolean shouldCancelSubscription(Throwable error) {
        return !isNoGroup(error);
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
