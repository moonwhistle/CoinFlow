package com.coinflow.chart.service.sync;

import com.coinflow.chart.repository.RedisOhlcWindowRepository;
import com.coinflow.domain.ohlc.snapshot.OhlcCandleSnapshot;
import com.coinflow.event.kline.KlineEvent;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.lang.NonNull;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.ZoneOffset;

/**
 * Service that listens for kline events and updates the Redis-based sliding window.
 * Ensures the chart cache remains consistent with the latest finalized candles.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OhlcWindowSyncService implements MessageListener {

    private final RedisOhlcWindowRepository ohlcWindowRepository;
    private final ObjectMapper objectMapper;

    private static final int DEFAULT_WINDOW_SIZE = 1000;

    @Override
    public void onMessage(@NonNull Message message, @Nullable byte[] pattern) {
        try {
            String json = new String(message.getBody());
            KlineEvent event = objectMapper.readValue(json, KlineEvent.class);

            // Our cache only stores finalized (closed) candles for pure data integrity.
            // Late ticks are also marked as closed: true, ensuring they update the ZSET.
            if (event.closed()) {
                OhlcCandleSnapshot snapshot = toSnapshot(event);
                ohlcWindowRepository.save(event.symbol(), event.interval(), snapshot);
                
                // Maintain the sliding window size
                ohlcWindowRepository.trim(event.symbol(), event.interval(), DEFAULT_WINDOW_SIZE);
                
                log.debug("[CHART-SYNC] Synchronized closed candle for {} {}: {}", 
                        event.symbol(), event.interval(), snapshot.bucketTime());
            }

        } catch (JsonProcessingException e) {
            log.error("Failed to deserialize KlineEvent for chart synchronization", e);
        } catch (Exception e) {
            log.error("Unexpected error in OhlcWindowSyncService", e);
        }
    }

    private OhlcCandleSnapshot toSnapshot(KlineEvent event) {
        LocalDateTime bucketTime = LocalDateTime.ofEpochSecond(event.startTime(), 0, ZoneOffset.UTC);
        return new OhlcCandleSnapshot(
                bucketTime,
                event.startTime(),
                event.open(),
                event.high(),
                event.low(),
                event.close(),
                event.volume()
        );
    }
}
