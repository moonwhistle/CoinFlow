package com.coinflow.publish.pubsub;

import static com.coinflow.monitoring.constant.MetricConstants.TAG_MODULE;
import static com.coinflow.monitoring.constant.MetricConstants.TICKER_PUBLISH_FAILURE_COUNT;
import static com.coinflow.monitoring.constant.MetricConstants.TICKER_PUBLISH_LATENCY;
import static com.coinflow.monitoring.constant.MetricConstants.VALUE_MODULE_COLLECTOR;

import com.coinflow.monitoring.MetricRecorder;
import com.coinflow.publish.exception.PublishErrorCode;
import com.coinflow.publish.exception.PublishException;
import com.coinflow.ticker.publisher.TickerPublisher;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;

@Slf4j
@RequiredArgsConstructor
public class RedisTickerPublisher implements TickerPublisher {

    private final StringRedisTemplate redisTemplate;
    private final MetricRecorder metricRecorder;
    private final String tickerTopic;

    @Override
    public void publish(String tickerPayload) {
        long startNanos = System.nanoTime();
        try {
            redisTemplate.convertAndSend(tickerTopic, tickerPayload);
        } catch (Exception e) {
            metricRecorder.increment(
                    TICKER_PUBLISH_FAILURE_COUNT,
                    TAG_MODULE,
                    VALUE_MODULE_COLLECTOR
            );
            log.error("Failed to publish ticker to Redis Pub/Sub. topic={}", tickerTopic, e);
            throw new PublishException(
                    PublishErrorCode.REDIS_PUBLISH_FAILED,
                    "Redis ticker publishing error",
                    e
            );
        } finally {
            metricRecorder.recordTimeNanos(
                    TICKER_PUBLISH_LATENCY,
                    System.nanoTime() - startNanos,
                    TAG_MODULE,
                    VALUE_MODULE_COLLECTOR
            );
        }
    }
}
