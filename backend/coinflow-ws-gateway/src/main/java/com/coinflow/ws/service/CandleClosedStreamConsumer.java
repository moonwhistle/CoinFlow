package com.coinflow.ws.service;

import com.coinflow.ws.service.kline.KlineAggregator;
import com.coinflow.event.ohlc.CandleClosedEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class CandleClosedStreamConsumer implements MessageListener {

    private final KlineAggregator klineAggregator;
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

            String normalizedSymbol = symbolCode.toLowerCase();

            // Feed into KlineAggregator — it will mark the candle as closed
            klineAggregator.processClose(
                    normalizedSymbol,
                    event.interval(),
                    event.epochSeconds(),
                    event.open(),
                    event.high(),
                    event.low(),
                    event.close(),
                    event.volume());

            log.debug("CandleClosedEvent processed for {}:{} at epoch={}",
                    normalizedSymbol, event.interval(), event.epochSeconds());

        } catch (Exception e) {
            log.error("Failed to process CandleClosed message", e);
        }
    }
}
