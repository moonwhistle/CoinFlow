package com.coinflow.client.binance;

import com.coinflow.client.DataClient;
import com.coinflow.handler.TickMessageHandler;
import java.net.URI;
import lombok.extern.slf4j.Slf4j;
import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;

@Slf4j
public class BinanceWebSocketClient implements DataClient {

    private final WebSocketClient client;

    public BinanceWebSocketClient(
            String url,
            TickMessageHandler handler
    ) {
        this.client = new WebSocketClient(URI.create(url)) {

            @Override
            public void onOpen(ServerHandshake handshake) {
                log.info("Binance WebSocket connected");
            }

            @Override
            public void onMessage(String message) {
                handler.handle(message);
            }

            @Override
            public void onClose(int code, String reason, boolean remote) {
                log.warn(
                        "Binance WebSocket closed. code={}, reason={}, remote={}",
                        code, reason, remote
                );
            }

            @Override
            public void onError(Exception ex) {
                log.error("Binance WebSocket error", ex);
            }
        };
    }

    @Override
    public void connect() {
        client.connect();
    }

    @Override
    public void disconnect() {
        client.close();
    }
}
