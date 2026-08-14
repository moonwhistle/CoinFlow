package com.coinflow.chart.service.sync;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.coinflow.chart.cache.hot.OhlcHotWindowStore;
import com.coinflow.event.kline.KlineEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.connection.Message;

@ExtendWith(MockitoExtension.class)
class OhlcWindowSyncServiceTest {

    @Mock
    private OhlcHotWindowStore hotWindowStore;

    @Mock
    private Message message;

    @Test
    void pubSubEventUpdatesOnlyTheLocalHotWindow() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        OhlcWindowSyncService service = new OhlcWindowSyncService(hotWindowStore, objectMapper);
        KlineEvent event = KlineEvent.builder()
                .symbol("btcusdt")
                .interval("M1")
                .startTime(60)
                .closeTime(119)
                .open(BigDecimal.ONE)
                .high(BigDecimal.TEN)
                .low(BigDecimal.ONE)
                .close(BigDecimal.TEN)
                .volume(BigDecimal.ONE)
                .trades(1)
                .closed(false)
                .build();
        when(message.getBody()).thenReturn(objectMapper.writeValueAsBytes(event));

        service.onMessage(message, null);

        verify(hotWindowStore).applyEvent(event);
    }
}
