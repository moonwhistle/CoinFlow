package com.coinflow.ws.integration;

import com.coinflow.WsGatewayApplication;
import com.coinflow.ws.service.CandleClosedStreamConsumer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.ActiveProfiles;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;

@SpringBootTest(classes = WsGatewayApplication.class)
@ActiveProfiles("test")
public class RedisPubSubIntegrationTest {

    @Autowired
    private StringRedisTemplate redisTemplate;

    @MockBean
    private CandleClosedStreamConsumer candleClosedStreamConsumer;

    @Test
    public void testPubSubWiring() {
        // Given
        String channel = "candle:closed";
        String message = "{\"symbolCode\":\"BTCUSDT\",\"close\":100000}";

        // When
        redisTemplate.convertAndSend(channel, message);

        // Then
        // Verify that the consumer's onMessage method is called
        // We use a timeout because Pub/Sub is asynchronous
        verify(candleClosedStreamConsumer, timeout(2000).times(1))
                .onMessage(any(), any());
    }
}
