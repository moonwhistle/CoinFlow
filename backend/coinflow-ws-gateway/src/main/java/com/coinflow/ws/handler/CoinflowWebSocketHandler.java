package com.coinflow.ws.handler;

import com.coinflow.ws.session.WebSocketSessionManager;
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

    @Override
    public Mono<Void> handle(WebSocketSession session) {
        String sessionId = session.getId();
        log.info("[WS] New connection established. SessionId={}", sessionId);

        sessionManager.addSession(session);

        // Handle incoming messages (if any) and wait for completion (disconnection)
        return session.receive()
                .doOnNext(message -> {
                    // Placeholder for future incoming message handling (e.g., subscription)
                    log.debug("[WS] Received message from {}: {}", sessionId, message.getPayloadAsText());
                })
                .doOnComplete(() -> {
                    log.info("[WS] Connection closed. SessionId={}", sessionId);
                    sessionManager.removeSession(sessionId);
                })
                .doOnError(t -> {
                    log.error("[WS] Connection error. SessionId={}", sessionId, t);
                    sessionManager.removeSession(sessionId);
                })
                .then();
    }
}
