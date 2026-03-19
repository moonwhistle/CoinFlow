package com.coinflow.consumer;

import com.coinflow.config.properties.TickConsumerProperties;
import com.coinflow.handler.TickRawMessageHandler;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.stream.StreamListener;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class TickRawEventConsumer implements StreamListener<String, MapRecord<String, String, String>> {

    private final TickRawMessageHandler handler;
    private final TickConsumerProperties properties;

    @Override
    public void onMessage(MapRecord<String, String, String> record) {
        // 실제 ACK는 비동기 작업(DB 저장 등)이 완료된 후 TickProcessService에서 수행함
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
