package com.coinflow.consumer;

import com.coinflow.config.properties.TickConsumerProperties;
import com.coinflow.handler.TickRawMessageHandler;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.stream.StreamListener;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class TickRawEventConsumer implements StreamListener<String, MapRecord<String, String, String>> {

    private final TickRawMessageHandler handler;
    private final RedisTemplate<String, String> redisTemplate;
    private final TickConsumerProperties properties;

    @Override
    public void onMessage(MapRecord<String, String, String> record) {
        boolean success = handler.handle(record.getValue());

        if (success) {
            redisTemplate.opsForStream()
                    .acknowledge(
                            properties.streamKey(),
                            properties.group(),
                            record.getId()
                    );
        } else {
            // 실패 > pending 유지
            log.debug("Processing failed. keep pending. recordId={}", record.getId());
        }
    }
}
