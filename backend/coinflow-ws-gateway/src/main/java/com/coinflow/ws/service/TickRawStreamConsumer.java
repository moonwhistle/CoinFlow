package com.coinflow.ws.service;

import com.coinflow.ws.dto.TickDto;
import com.coinflow.ws.session.SubscriptionSessionManager;
import com.coinflow.ws.session.WebSocketSessionManager;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.stream.StreamListener;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.socket.WebSocketMessage;
import org.springframework.web.reactive.socket.WebSocketSession;
import reactor.core.publisher.Flux;

@Slf4j
@Service
@RequiredArgsConstructor
public class TickRawStreamConsumer implements StreamListener<String, MapRecord<String, String, String>> {

    private final WebSocketSessionManager sessionManager;
    private final SubscriptionSessionManager subscriptionManager;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public void onMessage(MapRecord<String, String, String> message) {
        try {
            Map<String, String> body = message.getValue();
            String symbol = body.get("symbol");

            if (symbol == null) {
                log.debug("[Redis] Received tick without symbol, ignoring.");
                return;
            }

            // Map to DTO
            TickDto tickDto = TickDto.builder()
                    .symbol(symbol)
                    .price(new java.math.BigDecimal(body.get("price")))
                    .volume(new java.math.BigDecimal(body.get("quantity")))
                    .eventTime(java.time.Instant.parse(body.get("eventTime")).toEpochMilli())
                    .build();

            String jsonPayload = objectMapper.writeValueAsString(tickDto);
            log.trace("[Redis] Received tick for {}: {}", symbol, jsonPayload); // Changed to trace for high traffic

            // Get subscribers for this symbol
            var subscribers = subscriptionManager.getSubscribers(symbol);
            log.info("[Redis] Processing tick for symbol: '{}'. Found {} subscribers.", symbol, subscribers.size());

            subscribers.forEach(sessionId -> {
                WebSocketSession session = sessionManager.getSession(sessionId);

                if (session != null && session.isOpen()) {
                    WebSocketMessage wsMessage = session.textMessage(jsonPayload);
                    session.send(Flux.just(wsMessage))
                            .doOnError(e -> log.warn("Failed to send message to session {}", sessionId, e))
                            .subscribe();
                }
            });

        } catch (Exception e) {
            log.error("Failed to process redis stream message", e);
        }
    }
}
