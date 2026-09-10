package com.coinflow.handler.binance;

import static com.coinflow.monitoring.constant.MetricConstants.WEBSOCKET_RECEIVE_COUNT;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verify;

import com.coinflow.monitoring.MetricRecorder;
import com.coinflow.ticker.publisher.TickerPublisher;
import com.coinflow.tick.publisher.TickPublisher;
import com.coinflow.tick.serialization.TickRawBinaryCodec;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class BinanceTradeMessageHandlerTest {

    @Mock
    private TickPublisher publisher;

    @Mock
    private TickerPublisher tickerPublisher;

    @Mock
    private MetricRecorder metricRecorder;

    private ObjectMapper objectMapper;
    private BinanceTradeMessageHandler handler;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        handler = new BinanceTradeMessageHandler(objectMapper, publisher, tickerPublisher, metricRecorder);
    }

    @Test
    void publishesRawTickAndTickerPayloadTogether() throws Exception {
        String message = """
                {
                  "stream":"btcusdt@trade",
                  "data":{
                    "e":"trade",
                    "E":1711512345678,
                    "s":"BTCUSDT",
                    "t":12345,
                    "p":"65432.12345678",
                    "q":"0.001234",
                    "T":1711512345600
                  }
                }
                """;
        ArgumentCaptor<byte[]> rawCaptor = ArgumentCaptor.forClass(byte[].class);
        ArgumentCaptor<String> tickerCaptor = ArgumentCaptor.forClass(String.class);

        handler.handle(message);

        verify(metricRecorder).increment(WEBSOCKET_RECEIVE_COUNT);
        verify(publisher).publish(rawCaptor.capture());
        verify(tickerPublisher).publish(tickerCaptor.capture());
        InOrder publishOrder = inOrder(tickerPublisher, publisher);
        publishOrder.verify(tickerPublisher).publish(tickerCaptor.getValue());
        publishOrder.verify(publisher).publish(rawCaptor.getValue());
        assertThat(TickRawBinaryCodec.extractSymbol(rawCaptor.getValue())).isEqualTo("btcusdt");
        assertThat(TickRawBinaryCodec.extractPrice(rawCaptor.getValue())).isEqualByComparingTo("65432.12345678");
        assertThat(TickRawBinaryCodec.extractQuantity(rawCaptor.getValue())).isEqualByComparingTo("0.001234");
        assertThat(TickRawBinaryCodec.extractEventTime(rawCaptor.getValue())).isEqualTo(1711512345678L);

        JsonNode ticker = objectMapper.readTree(tickerCaptor.getValue());
        assertThat(ticker.get("symbol").asText()).isEqualTo("btcusdt");
        assertThat(ticker.get("price").decimalValue()).isEqualByComparingTo("65432.12345678");
        assertThat(ticker.get("volume").decimalValue()).isEqualByComparingTo("0.001234");
        assertThat(ticker.get("eventTime").asLong()).isEqualTo(1711512345678L);
    }

    @Test
    void doesNotPublishIncompleteTick() {
        handler.handle("{\"stream\":\"btcusdt@trade\",\"data\":{\"s\":\"BTCUSDT\"}}");

        verify(metricRecorder).increment(WEBSOCKET_RECEIVE_COUNT);
        verify(tickerPublisher, never()).publish(org.mockito.ArgumentMatchers.anyString());
        verify(publisher, never()).publish(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void stillStoresTickWhenTickerBroadcastFails() {
        doThrow(new RuntimeException("pubsub unavailable"))
                .when(tickerPublisher).publish(org.mockito.ArgumentMatchers.anyString());

        handler.handle(validMessage());

        verify(tickerPublisher).publish(org.mockito.ArgumentMatchers.anyString());
        verify(publisher).publish(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void stillBroadcastsTickerWhenStreamStoreFails() {
        doThrow(new RuntimeException("stream unavailable"))
                .when(publisher).publish(org.mockito.ArgumentMatchers.any());

        handler.handle(validMessage());

        verify(tickerPublisher).publish(org.mockito.ArgumentMatchers.anyString());
        verify(publisher).publish(org.mockito.ArgumentMatchers.any());
    }

    private String validMessage() {
        return """
                {"stream":"btcusdt@trade","data":{
                  "E":1711512345678,"s":"BTCUSDT","p":"65432.1","q":"0.001"
                }}
                """;
    }
}
