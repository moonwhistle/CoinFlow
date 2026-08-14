package com.coinflow.chart.service.sync;

import com.coinflow.chart.cache.hot.OhlcHotWindowStore;
import com.coinflow.event.kline.KlineEvent;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.lang.NonNull;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;

/**
 * Applies Pub/Sub events to the local hot window between periodic Redis reconciliations.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OhlcWindowSyncService implements MessageListener {

    private final OhlcHotWindowStore hotWindowStore;
    private final ObjectMapper objectMapper;

    @Override
    public void onMessage(@NonNull Message message, @Nullable byte[] pattern) {
        try {
            String json = new String(message.getBody(), StandardCharsets.UTF_8);
            KlineEvent event = objectMapper.readValue(json, KlineEvent.class);

            hotWindowStore.applyEvent(event);
            log.trace("[CHART-SYNC] Applied event to local window for {} {}: {}",
                    event.symbol(), event.interval(), event.startTime());

        } catch (JsonProcessingException e) {
            log.error("Failed to deserialize KlineEvent for chart synchronization", e);
        } catch (Exception e) {
            log.error("Unexpected error in OhlcWindowSyncService", e);
        }
    }

}
