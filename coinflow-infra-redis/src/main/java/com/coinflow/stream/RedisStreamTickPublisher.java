package com.coinflow.stream;

import static com.coinflow.stream.constant.TickStreamFields.EVENT_TIME;
import static com.coinflow.stream.constant.TickStreamFields.PRICE;
import static com.coinflow.stream.constant.TickStreamFields.QUANTITY;
import static com.coinflow.stream.constant.TickStreamFields.SYMBOL;

import com.coinflow.domain.tick.event.TickRawEvent;
import com.coinflow.domain.tick.publisher.TickPublisher;
import com.coinflow.exception.PublishErrorCode;
import com.coinflow.exception.PublishException;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.core.RedisTemplate;

@RequiredArgsConstructor
@Slf4j
public class RedisStreamTickPublisher implements TickPublisher {

    public static final String RAW_TICK_STREAM = "tick:raw";

    private final RedisTemplate<String, String> redisTemplate;

    @Override
    public void publish(TickRawEvent event) {
        try {
            Map<String, String> fields = Map.of(
                    SYMBOL, event.symbol(),
                    PRICE, event.price().toPlainString(),
                    QUANTITY, event.quantity().toPlainString(),
                    EVENT_TIME, event.eventTime().toString()
            );

            RecordId recordId = redisTemplate.opsForStream()
                    .add(RAW_TICK_STREAM, fields);

            assert recordId != null;
            log.debug(
                    "Published tick event. stream={}, recordId={}, symbol={}",
                    RAW_TICK_STREAM, recordId.getValue(), event.symbol()
            );
        } catch (Exception e) {
            // TODO: 매트릭 수집 및 재시도 로직
            throw new PublishException(
                    PublishErrorCode.REDIS_PUBLISH_FAILED,
                    "Failed to publish tick event to Redis Streams",
                    e
            );
        }
    }
}
