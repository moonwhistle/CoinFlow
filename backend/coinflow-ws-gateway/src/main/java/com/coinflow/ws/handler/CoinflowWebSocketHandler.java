package com.coinflow.ws.handler;

import com.coinflow.ws.model.WsCommandType;
import com.coinflow.ws.model.WsRequest;
import com.coinflow.ws.session.SubscriptionSessionManager;
import com.coinflow.ws.session.WebSocketSessionManager;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.socket.WebSocketHandler;
import org.springframework.web.reactive.socket.WebSocketSession;
import reactor.core.publisher.Mono;

@Slf4j
@Component
@RequiredArgsConstructor
public class CoinflowWebSocketHandler implements WebSocketHandler {

    private final WebSocketSessionManager sessionManager;
    private final SubscriptionSessionManager subscriptionManager;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public Mono<Void> handle(WebSocketSession session) {
        String sessionId = session.getId();
        log.info("[WS] New connection established. SessionId={}", sessionId);

        sessionManager.addSession(session);

        // Handle incoming messages (if any) and wait for completion (disconnection)
        return session.receive()
                .flatMap(message -> {
                    try {
                        String payload = message.getPayloadAsText();
                        log.info("[WS] Received message from {}: {}", sessionId, payload);
                        WsRequest request = objectMapper.readValue(payload, WsRequest.class);
                        handleRequest(sessionId, request);
                    } catch (Exception e) {
                        log.error("[WS] Failed to parse message from {}", sessionId, e);
                    }

                    return Mono.empty();
                })
                .doOnComplete(() -> {
                    log.info("[WS] Connection closed. SessionId={}", sessionId);
                    sessionManager.removeSession(sessionId);
                    subscriptionManager.removeSession(sessionId);
                })
                .doOnError(t -> {
                    log.error("[WS] Connection error. SessionId={}", sessionId, t);
                    sessionManager.removeSession(sessionId);
                    subscriptionManager.removeSession(sessionId);
                })
                .then();
    }

    private void handleRequest(String sessionId, WsRequest request) {
        if (request.getType() == null || request.getTopics() == null) {
            log.warn("[WS] Invalid request from {}: {}", sessionId, request);

            return;
        }

        if (request.getType() == WsCommandType.SUBSCRIBE) {
            handleSubscribe(sessionId, request.getTopics());
        } else if (request.getType() == WsCommandType.UNSUBSCRIBE) {
            handleUnsubscribe(sessionId, request.getTopics());
        } else {
            log.warn("[WS] Unknown command type: {}", request.getType());
        }
    }

    private void handleSubscribe(String sessionId, List<WsRequest.WsSubscription> topics) {
        topics.forEach(topic -> {
            log.info("[WS] Session {} subscribed to {}", sessionId, topic.getSymbol());
            subscriptionManager.subscribe(sessionId, topic.getSymbol());
        });
    }

    private void handleUnsubscribe(String sessionId, List<WsRequest.WsSubscription> topics) {
        topics.forEach(topic -> {
            log.info("[WS] Session {} unsubscribed from {}", sessionId, topic.getSymbol());
            subscriptionManager.unsubscribe(sessionId, topic.getSymbol());
        });
    }
}
