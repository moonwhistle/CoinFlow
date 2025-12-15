package com.coinflow.client.binance.support;

import com.coinflow.config.properties.BinanceWebSocketProperties;
import java.util.stream.Collectors;

public class BinanceStreamUrlBuilder {

    public static final String STREAM_QUERY_PARAM = "?streams=";
    public static final String STREAM_DELIMITER = "/";

    private final BinanceWebSocketProperties properties;

    public BinanceStreamUrlBuilder(BinanceWebSocketProperties properties) {
        this.properties = properties;
    }

    public String build() {
        String streams = properties.symbols().stream()
                .map(symbol -> symbol + properties.tradeStreamSuffix())
                .collect(Collectors.joining(STREAM_DELIMITER));

        return properties.baseUrl() + STREAM_QUERY_PARAM + streams;
    }
}
