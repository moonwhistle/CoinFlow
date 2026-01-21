package com.coinflow.ws.service;

import com.coinflow.ws.session.WebSocketSessionManager;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.stream.StreamListener;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.socket.WebSocketMessage;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@Slf4j
@Service
@RequiredArgsConstructor
public class TickRawStreamConsumer implements StreamListener<String, MapRecord<String, String, String>> {

    private final WebSocketSessionManager sessionManager;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public void onMessage(MapRecord<String, String, String> message) {
        try {
            Map<String, String> body = message.getValue();
            // In a real scenario, we might map this to a DTO (TickRawEvent)
            String jsonPayload = objectMapper.writeValueAsString(body);

            log.info("[Redis] Received tick: {}", jsonPayload);

            // Broadcast to ALL connected sessions (Naive implementation)
            // Ideally, we should filter by subscription (e.g., symbol)
            Flux.fromIterable(sessionManager.getAllSessions())
                    .flatMap(session -> {
                        if (session.isOpen()) {
                            WebSocketMessage wsMessage = session.textMessage(jsonPayload);
                            return session.send(Flux.just(wsMessage))
                                    .onErrorResume(e -> {
                                        log.warn("Failed to send message to session {}", session.getId(), e);
                                        return Mono.empty();
                                    });
                        }
                        return Mono.empty();
                    })
                    .subscribe();

        } catch (Exception e) {
            log.error("Failed to process redis stream message", e);
        }
    }
}
