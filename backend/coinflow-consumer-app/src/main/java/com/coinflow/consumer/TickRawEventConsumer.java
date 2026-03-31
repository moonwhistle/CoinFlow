package com.coinflow.consumer;

import com.coinflow.config.properties.TickConsumerProperties;
import com.coinflow.handler.TickRawMessageHandler;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.stream.StreamListener;
import org.springframework.stereotype.Component;

/**
 * Redis Stream으로부터 바이너리 데이터를 수신하여 핸들러에게 위임하는 컨슈머 클래스입니다.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class TickRawEventConsumer implements StreamListener<String, MapRecord<String, String, byte[]>> {

    private final TickRawMessageHandler handler;
    private final TickConsumerProperties properties;

    @Override
    public void onMessage(MapRecord<String, String, byte[]> record) {
        log.debug("Received raw binary tick from stream. recordId={}", record.getId());

        // [Phase 3.2] 바이너리 데이터와 메타데이터를 핸들러에 전달하여 처리 루틴 시작
        boolean submitted = handler.handle(
                record.getValue(), 
                properties.streamKey(), 
                properties.group(), 
                record.getId());

        if (!submitted) {
            log.error("Failed to submit message for processing. recordId={}", record.getId());
        }
    }
}
