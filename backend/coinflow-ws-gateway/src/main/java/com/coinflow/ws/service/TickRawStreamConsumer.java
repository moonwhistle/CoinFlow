package com.coinflow.ws.service;

import com.coinflow.ws.dto.KlineEvent;
import com.coinflow.ws.service.kline.KlineAggregator;
import com.coinflow.ws.service.kline.KlineAggregator.ClosedKlineSnapshot;
import com.coinflow.ws.service.kline.KlineState.KlineSnapshot;
import com.coinflow.ws.session.SubscriptionSessionManager;
import com.coinflow.ws.session.WebSocketSessionManager;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
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

    private final KlineAggregator klineAggregator;
    private final SubscriptionSessionManager subscriptionManager;
    private final WebSocketSessionManager sessionManager;
    private final ObjectMapper objectMapper;

    @Override
    public void onMessage(MapRecord<String, String, String> message) {
        try {
            Map<String, String> body = message.getValue();
            String symbol = body.get("symbol");

            if (symbol == null) {
                log.debug("[Redis] Received tick without symbol, ignoring.");
                return;
            }

            BigDecimal price = new BigDecimal(body.get("price"));
            BigDecimal quantity = new BigDecimal(body.get("quantity"));
            long epochMs = Instant.parse(body.get("eventTime")).toEpochMilli();

            // Feed tick into KlineAggregator and get any candles that just closed
            List<ClosedKlineSnapshot> closedCandles = klineAggregator.processTickAndGetClosed(symbol, price, quantity,
                    epochMs);

            log.trace("[Redis] Tick processed for {}: price={}, qty={}", symbol, price, quantity);

            // Immediately broadcast closed candles
            if (!closedCandles.isEmpty()) {
                var subscribers = subscriptionManager.getSubscribers(symbol);
                if (subscribers.isEmpty())
                    return;

                for (ClosedKlineSnapshot closedData : closedCandles) {
                    KlineSnapshot snapshot = closedData.snapshot();

                    KlineEvent event = KlineEvent.builder()
                            .symbol(symbol)
                            .interval(closedData.interval())
                            .startTime(snapshot.startTime())
                            .closeTime(snapshot.closeTime())
                            .open(snapshot.open())
                            .high(snapshot.high())
                            .low(snapshot.low())
                            .close(snapshot.close())
                            .volume(snapshot.volume())
                            .trades(snapshot.trades())
                            .closed(snapshot.closed()) // This will be true
                            .build();

                    String json = objectMapper.writeValueAsString(event);

                    subscribers.forEach(sessionId -> {
                        WebSocketSession session = sessionManager.getSession(sessionId);
                        if (session != null && session.isOpen()) {
                            WebSocketMessage wsMessage = session.textMessage(json);
                            session.send(Flux.just(wsMessage))
                                    .doOnError(e -> log.warn("Failed to send closed kline to session {}", sessionId, e))
                                    .subscribe();
                        }
                    });

                    log.debug("Broadcasted closed kline on tick transition for {}:{}", symbol, closedData.interval());
                }
            }

        } catch (Exception e) {
            log.error("Failed to process redis stream message", e);
        }
    }
}
