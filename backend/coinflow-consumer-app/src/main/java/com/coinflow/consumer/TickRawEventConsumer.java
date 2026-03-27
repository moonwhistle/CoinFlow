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
        // [Phase 3.1] 바이너리 수신 지원을 위해 제네릭 타입 변경
        // [Phase 3.2]에서 핸들러가 byte[]를 직접 받도록 수정할 예정입니다.
        // 현재는 호환성을 위해 우선 바이너리 수신 상태만 활성화합니다.
        
        log.debug("Received raw binary tick from stream. recordId={}", record.getId());

        // TODO: 3.2 단계에서 핸들러 호출 방식을 바이너리 기반으로 변경
        // boolean submitted = handler.handle(record.getValue(), ...);
    }
}
