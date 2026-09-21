package com.coinflow.publish.stream;

import com.coinflow.monitoring.MetricRecorder;
import com.coinflow.publish.exception.PublishErrorCode;
import com.coinflow.publish.exception.PublishException;
import com.coinflow.tick.publisher.TickPublisher;
import java.util.Map;
import java.util.List;
import java.util.concurrent.Callable;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.RedisStreamCommands.XAddOptions;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.connection.stream.StreamRecords;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.serializer.RedisSerializer;

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

    private final RedisTemplate<String, byte[]> rawRedisTemplate;
    private final MetricRecorder metricRecorder;
    private final String streamKey;
    private final long maxLength;
    private final PublishObserver publishObserver;

    public RedisStreamTickPublisher(
            RedisTemplate<String, byte[]> rawRedisTemplate,
            MetricRecorder metricRecorder,
            String streamKey,
            long maxLength
    ) {
        this(rawRedisTemplate, metricRecorder, streamKey, maxLength, (payloads, roundTripNanos) -> {});
    }

    public RedisStreamTickPublisher(
            RedisTemplate<String, byte[]> rawRedisTemplate,
            MetricRecorder metricRecorder,
            String streamKey,
            long maxLength,
            PublishObserver publishObserver
    ) {
        this.rawRedisTemplate = rawRedisTemplate;
        this.metricRecorder = metricRecorder;
        this.streamKey = streamKey;
        this.maxLength = maxLength;
        this.publishObserver = publishObserver;
    }

    /**
     * 최적화된 바이너리 방식 (Zero-POJO)
     */
    @Override
    public void publish(byte[] rawData) {
        MapRecord<String, String, byte[]> record = StreamRecords.newRecord()
                .in(streamKey)
                .ofMap(Map.of(RAW_PAYLOAD_FIELD, rawData));

        XAddOptions options = maxLength > 0 ? XAddOptions.maxlen(maxLength).approximateTrimming(true) : XAddOptions.none();

        long started = System.nanoTime();
        RecordId recordId = executePublish(() -> rawRedisTemplate.opsForStream().add(record, options));
        notifyConfirmed(List.of(rawData), System.nanoTime() - started);

        log.debug("Published raw tick data. stream={}, recordId={}, maxlen={}",
                streamKey, recordId.getValue(), maxLength);
    }

    /** Sends one XADD per payload in a single Redis pipeline round trip. */
    public void publishBatch(List<byte[]> payloads) {
        if (payloads.isEmpty()) {
            return;
        }

        byte[] key = rawRedisTemplate.getStringSerializer().serialize(streamKey);
        byte[] field = rawRedisTemplate.getStringSerializer().serialize(RAW_PAYLOAD_FIELD);
        XAddOptions options = maxLength > 0 ? XAddOptions.maxlen(maxLength).approximateTrimming(true) : XAddOptions.none();

        long started = System.nanoTime();
        try {
            List<Object> replies = metricRecorder.recordTime(STREAM_PUBLISH_LATENCY, () ->
                    rawRedisTemplate.executePipelined((RedisCallback<Object>) connection -> {
                        for (byte[] payload : payloads) {
                            MapRecord<byte[], byte[], byte[]> record = StreamRecords.newRecord()
                                    .in(key)
                                    .ofMap(Map.of(field, payload));
                            connection.streamCommands().xAdd(record, options);
                        }
                        return null;
                    }, RedisSerializer.byteArray()));

            if (replies.size() != payloads.size() || replies.stream().anyMatch(java.util.Objects::isNull)) {
                throw new PublishException(PublishErrorCode.REDIS_PUBLISH_FAILED,
                        "Incomplete Redis pipeline response", null);
            }
            notifyConfirmed(payloads, System.nanoTime() - started);
        } catch (Exception e) {
            metricRecorder.increment(STREAM_PUBLISH_FAILURE_COUNT, TAG_MODULE, VALUE_MODULE_COLLECTOR);
            if (e instanceof PublishException publishException) {
                throw publishException;
            }
            throw new PublishException(PublishErrorCode.REDIS_PUBLISH_FAILED,
                    "Redis Stream pipeline publishing error", e);
        }
    }

    private void notifyConfirmed(List<byte[]> payloads, long roundTripNanos) {
        try {
            publishObserver.onConfirmed(payloads, roundTripNanos);
        } catch (RuntimeException e) {
            // Observability must never turn an already-confirmed XADD into a retry.
            log.warn("Redis publish observer failed after XADD confirmation", e);
        }
    }

    @FunctionalInterface
    public interface PublishObserver {
        void onConfirmed(List<byte[]> payloads, long roundTripNanos);
    }

    /**
     * 레이턴시 측정 및 예외 처리를 공통으로 수행하는 헬퍼 메서드 (DRY)
     */
    private RecordId executePublish(Callable<RecordId> publishAction) {
        try {
            RecordId recordId = metricRecorder.recordTime(STREAM_PUBLISH_LATENCY, publishAction);
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
