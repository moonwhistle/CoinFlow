package com.coinflow.ws.service.kline;

import com.coinflow.ws.dto.KlineEvent;
import com.coinflow.ws.session.SubscriptionSessionManager;
import com.coinflow.ws.session.WebSocketSessionManager;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.socket.WebSocketMessage;
import org.springframework.web.reactive.socket.WebSocketSession;
import reactor.core.publisher.Flux;

/**
 * Broadcasts kline snapshots every 1 second to all subscribed WebSocket
 * clients.
 * Mirrors Binance's kline stream behavior (1-2 second updates).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class KlineSnapshotBroadcaster {

    private final KlineAggregator aggregator;
    private final SubscriptionSessionManager subscriptionManager;
    private final WebSocketSessionManager sessionManager;
    private final ObjectMapper objectMapper;

    @Scheduled(fixedRate = 1000)
    public void broadcastSnapshots() {
        for (String symbol : aggregator.getActiveSymbols()) {
            var subscribers = subscriptionManager.getSubscribers(symbol);
            if (subscribers.isEmpty())
                continue;

            for (var interval : aggregator.getIntervals()) {
                KlineState.KlineSnapshot snapshot = aggregator.takeSnapshot(symbol, interval.name());
                if (snapshot == null)
                    continue;

                try {
                    KlineEvent event = KlineEvent.builder()
                            .symbol(symbol)
                            .interval(interval.name())
                            .startTime(snapshot.startTime())
                            .closeTime(snapshot.closeTime())
                            .open(snapshot.open())
                            .high(snapshot.high())
                            .low(snapshot.low())
                            .close(snapshot.close())
                            .volume(snapshot.volume())
                            .trades(snapshot.trades())
                            .closed(snapshot.closed())
                            .build();

                    String json = objectMapper.writeValueAsString(event);

                    subscribers.forEach(sessionId -> {
                        WebSocketSession session = sessionManager.getSession(sessionId);
                        if (session != null && session.isOpen()) {
                            WebSocketMessage wsMessage = session.textMessage(json);
                            session.send(Flux.just(wsMessage))
                                    .doOnError(e -> log.warn("Failed to send kline to session {}",
                                            sessionId, e))
                                    .subscribe();
                        }
                    });

                    if (snapshot.closed()) {
                        log.debug("Broadcasted closed kline for {}:{}", symbol, interval.name());
                        aggregator.resetAfterClose(symbol, interval.name());
                    }

                } catch (Exception e) {
                    log.error("Failed to broadcast kline for {}:{}", symbol, interval.name(), e);
                }
            }
        }
    }
}
