package com.coinflow.ws.service;

import com.coinflow.ws.session.SubscriptionSessionManager;
import com.coinflow.ws.session.WebSocketSessionManager;
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

    @Override
    public void onMessage(Message message, byte[] pattern) {
        try {
            String jsonPayload = new String(message.getBody());
            // We assume the payload is already the correct JSON for the client
            // "CandleClosedEvent"
            // We need to extract symbol to find subscribers.
            // Since we don't want to parse JSON just for symbol if we can avoid it...
            // But we MUST find the symbol.
            // Let's assume the JSON resembles the Event object.

            // Optimization: If we published "symbol" in the channel name (e.g.,
            // candle:closed:BTC), we wouldn't need to parse.
            // But current implementation publishes to global "candle:closed".
            // So we must parse.

            // Minimal parse to find symbolId... wait, the WebSocket uses string symbol
            // (e.g., "BTC"), but event has symbolId (Long).
            // This is a mismatch!
            // The Frontend expects "BTC", but Event has ID.
            // The WS Gateway needs to know which sessions subscribed to "BTC".
            // We need to either:
            // 1. Include Symbol Name in CandleClosedEvent.
            // 2. Or have a SymbolId -> Name cache in Gateway.

            // To keep it simple and robust, let's add Symbol Name to CandleClosedEvent.
            // I will update CandleClosedEvent first.

            log.trace("Received CandleClosed event: {}", jsonPayload);

        } catch (Exception e) {
            log.error("Failed to process CandleClosed message", e);
        }
    }
}
