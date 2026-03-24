package com.coinflow.publish.stream;

import static com.coinflow.tick.event.constant.TickStreamFields.EVENT_TIME;
import static com.coinflow.tick.event.constant.TickStreamFields.PRICE;
import static com.coinflow.tick.event.constant.TickStreamFields.QUANTITY;
import static com.coinflow.tick.event.constant.TickStreamFields.SYMBOL;

import com.coinflow.tick.event.TickRawEvent;
import com.coinflow.tick.publisher.TickPublisher;
import com.coinflow.publish.exception.PublishErrorCode;
import com.coinflow.publish.exception.PublishException;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.core.RedisTemplate;

import com.coinflow.monitoring.MetricRecorder;
import static com.coinflow.monitoring.constant.MetricConstants.STREAM_PUBLISH_LATENCY;

@RequiredArgsConstructor
@Slf4j
public class RedisStreamTickPublisher implements TickPublisher {

    public static final String RAW_TICK_STREAM = "tick:raw";

    private final RedisTemplate<String, String> redisTemplate;
    private final MetricRecorder metricRecorder;

    @Override
    public void publish(TickRawEvent event) {
        try {
            Map<String, String> fields = Map.of(
                    SYMBOL, event.symbol(),
                    PRICE, event.price().toPlainString(),
                    QUANTITY, event.quantity().toPlainString(),
                    EVENT_TIME, event.eventTime().toString()
            );

            RecordId recordId = metricRecorder.recordTime(
                    STREAM_PUBLISH_LATENCY, 
                    () -> redisTemplate.opsForStream().add(RAW_TICK_STREAM, fields)
            );

            if (recordId == null) {
                throw new PublishException(
                        PublishErrorCode.REDIS_PUBLISH_FAILED,
                        "RecordId is null after publishing to Redis Stream",
                        null
                );
            }

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
