package com.coinflow.ws.service;

import com.coinflow.ws.session.WebSocketSessionManager;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.stream.StreamListener;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.socket.WebSocketSession;
import reactor.core.publisher.Mono;

@Slf4j
@Service
@RequiredArgsConstructor
public class TickRawStreamConsumer implements StreamListener<String, MapRecord<String, String, String>> {

    private final WebSocketSessionManager sessionManager;
    private final ObjectMapper objectMapper;

    @Override
    public void onMessage(MapRecord<String, String, String> message) {
        Map<String, String> value = message.getValue();
        // Convert to JSON
        try {
            String jsonPayload = objectMapper.writeValueAsString(value);

            // Broadcast to all sessions
            for (WebSocketSession session : sessionManager.getAllSessions()) {
                if (session.isOpen()) {
                    session.send(Mono.just(session.textMessage(jsonPayload)))
                            .subscribe(
                                    null,
                                    error -> log.error("Failed to send message to session {}", session.getId(), error));
                }
            }
        } catch (JsonProcessingException e) {
            log.error("Failed to serialize tick data", e);
        }
    }
}
