package com.coinflow.ws.service;

import com.coinflow.ws.session.SubscriptionSessionManager;
import com.coinflow.ws.session.WebSocketSessionManager;
import com.coinflow.event.ohlc.CandleClosedEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.socket.WebSocketMessage;
import org.springframework.web.reactive.socket.WebSocketSession;
import reactor.core.publisher.Flux;

@Slf4j
@Service
@RequiredArgsConstructor
public class CandleClosedStreamConsumer implements MessageListener {

    private final WebSocketSessionManager sessionManager;
    private final SubscriptionSessionManager subscriptionManager;
    private final ObjectMapper objectMapper;

    @Override
    public void onMessage(Message message, byte[] pattern) {
        try {
            CandleClosedEvent event = objectMapper.readValue(message.getBody(), CandleClosedEvent.class);
            String symbolCode = event.symbolCode();

            if (symbolCode == null) {
                log.warn("Received CandleClosedEvent without symbolCode");
                return;
            }

            // Iterate over subscribers for this symbol
            subscriptionManager.getSubscribers(symbolCode).forEach(sessionId -> {
                WebSocketSession session = sessionManager.getSession(sessionId);
                if (session != null && session.isOpen()) {
                    // Forward the event as JSON to the client.
                    String originalPayload = new String(message.getBody());
                    WebSocketMessage wsMessage = session.textMessage(originalPayload);

                    session.send(Flux.just(wsMessage))
                            .doOnError(
                                    e -> log.warn("Failed to broadcast CandleClosedEvent to session {}", sessionId, e))
                            .subscribe();
                }
            });

            log.trace("Broadcasted CandleClosedEvent for {}", symbolCode);

        } catch (Exception e) {
            log.error("Failed to process CandleClosed message", e);
        }
    }
}
