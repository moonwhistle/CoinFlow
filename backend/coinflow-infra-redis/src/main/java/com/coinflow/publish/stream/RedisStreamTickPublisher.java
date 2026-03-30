package com.coinflow.publish.stream;

import com.coinflow.monitoring.MetricRecorder;
import static com.coinflow.monitoring.constant.MetricConstants.STREAM_PUBLISH_LATENCY;

import com.coinflow.publish.exception.PublishErrorCode;
import com.coinflow.publish.exception.PublishException;
import com.coinflow.tick.publisher.TickPublisher;
import java.util.Map;
import java.util.concurrent.Callable;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.RedisStreamCommands.XAddOptions;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.connection.stream.StreamRecords;
import org.springframework.data.redis.core.RedisTemplate;

import static com.coinflow.monitoring.constant.MetricConstants.*;

/**
 * Redis Stream을 통해 바이너리 틱 데이터를 전송하는 구현체입니다.
 */
@Slf4j
@RequiredArgsConstructor
public class RedisStreamTickPublisher implements TickPublisher {

    public static final String RAW_TICK_STREAM = "tick:raw";
    public static final String RAW_PAYLOAD_FIELD = "p";

    private final RedisTemplate<String, byte[]> rawRedisTemplate;
    private final MetricRecorder metricRecorder;

    /**
     * 최적화된 바이너리 방식 (Zero-POJO)
     */
    @Override
    public void publish(byte[] rawData) {
        // MapRecord 생성 (String, String, byte[])
        MapRecord<String, String, byte[]> record = StreamRecords.newRecord()
                .in(RAW_TICK_STREAM)
                .ofMap(Map.of(RAW_PAYLOAD_FIELD, rawData));

        // MAXLEN ~ 1,000,000 설정을 통한 자동 트리밍 (XAddOptions)
        XAddOptions options = XAddOptions.maxlen(STREAM_MAX_LEN).approximateTrimming(true);

        RecordId recordId = executePublish(() -> rawRedisTemplate.opsForStream().add(record, options));

        log.debug("Published raw tick data. stream={}, recordId={}, maxlen={}", 
                RAW_TICK_STREAM, recordId.getValue(), STREAM_MAX_LEN);
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
            log.error("Failed to publish tick data to Redis Stream: {}", e.getMessage());
            if (e instanceof PublishException) {
                throw (PublishException) e;
            }
            throw new PublishException(PublishErrorCode.REDIS_PUBLISH_FAILED, 
                    "Redis Stream publishing error", e);
        }
    }
}
