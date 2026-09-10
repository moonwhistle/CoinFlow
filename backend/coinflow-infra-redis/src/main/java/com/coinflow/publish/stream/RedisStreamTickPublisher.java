package com.coinflow.publish.stream;

import com.coinflow.monitoring.MetricRecorder;
import com.coinflow.publish.exception.PublishErrorCode;
import com.coinflow.publish.exception.PublishException;
import com.coinflow.tick.publisher.TickPublisher;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.Callable;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.data.redis.serializer.RedisSerializer;
import org.springframework.data.redis.serializer.StringRedisSerializer;

import static com.coinflow.monitoring.constant.MetricConstants.STREAM_PUBLISH_FAILURE_COUNT;
import static com.coinflow.monitoring.constant.MetricConstants.STREAM_PUBLISH_LATENCY;
import static com.coinflow.monitoring.constant.MetricConstants.TAG_MODULE;
import static com.coinflow.monitoring.constant.MetricConstants.VALUE_MODULE_COLLECTOR;

/**
 * Redis Stream을 통해 바이너리 틱 데이터를 전송하는 구현체입니다.
 */
@Slf4j
public class RedisStreamTickPublisher implements TickPublisher {

    public static final String RAW_PAYLOAD_FIELD = "p";
    private static final RedisScript<String> STORE_AND_BROADCAST_SCRIPT = new DefaultRedisScript<>("""
            local recordId = redis.call(
                'XADD', KEYS[1], 'MAXLEN', '~', ARGV[1], '*', 'p', ARGV[2]
            )
            redis.call('PUBLISH', KEYS[2], ARGV[3])
            return recordId
            """, String.class);

    private final RedisTemplate<String, byte[]> rawRedisTemplate;
    private final MetricRecorder metricRecorder;
    private final String streamKey;
    private final String tickerTopic;
    private final long maxLength;
    private final List<String> scriptKeys;
    private final byte[] maxLengthBytes;

    public RedisStreamTickPublisher(
            RedisTemplate<String, byte[]> rawRedisTemplate,
            MetricRecorder metricRecorder,
            String streamKey,
            String tickerTopic,
            long maxLength
    ) {
        this.rawRedisTemplate = rawRedisTemplate;
        this.metricRecorder = metricRecorder;
        this.streamKey = streamKey;
        this.tickerTopic = tickerTopic;
        this.maxLength = maxLength;
        this.scriptKeys = List.of(streamKey, tickerTopic);
        this.maxLengthBytes = Long.toString(maxLength).getBytes(StandardCharsets.UTF_8);
    }

    /**
     * Stream 저장과 현재가 Pub/Sub 발행을 Redis Lua 스크립트 하나로 실행합니다.
     */
    @Override
    public void publish(byte[] rawData, String tickerPayload) {
        String recordId = executePublish(() -> rawRedisTemplate.execute(
                STORE_AND_BROADCAST_SCRIPT,
                RedisSerializer.byteArray(),
                StringRedisSerializer.UTF_8,
                scriptKeys,
                maxLengthBytes,
                rawData,
                tickerPayload.getBytes(StandardCharsets.UTF_8)
        ));

        log.debug("Stored and broadcast raw tick. stream={}, topic={}, recordId={}, maxlen={}",
                streamKey, tickerTopic, recordId, maxLength);
    }

    /**
     * 레이턴시 측정 및 예외 처리를 공통으로 수행하는 헬퍼 메서드 (DRY)
     */
    private String executePublish(Callable<String> publishAction) {
        try {
            String recordId = metricRecorder.recordTime(STREAM_PUBLISH_LATENCY, publishAction);
            if (recordId == null) {
                throw new PublishException(PublishErrorCode.REDIS_PUBLISH_FAILED, 
                        "RecordId is null after Redis publishing", null);
            }
            return recordId;
        } catch (Exception e) {
            metricRecorder.increment(
                    STREAM_PUBLISH_FAILURE_COUNT,
                    TAG_MODULE,
                    VALUE_MODULE_COLLECTOR);
            log.error("Failed to publish tick data to Redis Stream", e);
            if (e instanceof PublishException) {
                throw (PublishException) e;
            }
            throw new PublishException(PublishErrorCode.REDIS_PUBLISH_FAILED, 
                    "Redis Stream publishing error", e);
        }
    }
}
