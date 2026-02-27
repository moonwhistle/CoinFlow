package com.coinflow.ws.service;

import com.coinflow.ws.service.kline.KlineAggregator;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.stream.StreamListener;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class TickRawStreamConsumer implements StreamListener<String, MapRecord<String, String, String>> {

    private final KlineAggregator klineAggregator;

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

            // Feed tick into KlineAggregator — no more raw tick forwarding
            klineAggregator.processTick(symbol, price, quantity, epochMs);

            log.trace("[Redis] Tick processed for {}: price={}, qty={}", symbol, price, quantity);

        } catch (Exception e) {
            log.error("Failed to process redis stream message", e);
        }
    }
}
