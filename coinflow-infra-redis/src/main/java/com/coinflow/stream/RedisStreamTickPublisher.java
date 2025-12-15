package com.coinflow.stream;

import com.coinflow.domain.tick.event.TickRawEvent;
import com.coinflow.domain.tick.publisher.TickPublisher;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.RedisTemplate;

@RequiredArgsConstructor
public class RedisStreamTickPublisher implements TickPublisher {

    public static final String RAW_TICK_STREAM = "tick:raw";

    private final RedisTemplate<String, String> redisTemplate;

    @Override
    public void publish(TickRawEvent event) {
        Map<String, String> fields = Map.of(
                "symbol", event.symbol(),
                "price", event.price().toPlainString(),
                "quantity", event.quantity().toPlainString(),
                "eventTime", event.eventTime().toString()
        );

        redisTemplate.opsForStream()
                .add(RAW_TICK_STREAM, fields);
    }
}
