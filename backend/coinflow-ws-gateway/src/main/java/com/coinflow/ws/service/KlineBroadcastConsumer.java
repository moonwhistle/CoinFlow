package com.coinflow.ws.service;

import com.coinflow.event.kline.KlineEvent;
import com.coinflow.ws.session.SubscriptionSessionManager;
import com.coinflow.ws.session.WebSocketSessionManager;
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
public class KlineBroadcastConsumer implements MessageListener {

    private final SubscriptionSessionManager subscriptionManager;
    private final WebSocketSessionManager sessionManager;
    private final ObjectMapper objectMapper;

    @Override
    public void onMessage(Message message, byte[] pattern) {
        try {
            String json = new String(message.getBody());
            KlineEvent event = objectMapper.readValue(json, KlineEvent.class);
            String symbol = event.symbol();

            var subscribers = subscriptionManager.getSubscribers(symbol);
            if (subscribers.isEmpty())
                return;

            subscribers.forEach(sessionId -> {
                WebSocketSession session = sessionManager.getSession(sessionId);
                if (session != null && session.isOpen()) {
                    WebSocketMessage wsMessage = session.textMessage(json);
                    session.send(Flux.just(wsMessage))
                            .doOnError(e -> log.warn("Failed to send kline to session {}", sessionId, e))
                            .subscribe();
                }
            });

        } catch (Exception e) {
            log.error("Failed to process kline broadcast message", e);
        }
    }
}
