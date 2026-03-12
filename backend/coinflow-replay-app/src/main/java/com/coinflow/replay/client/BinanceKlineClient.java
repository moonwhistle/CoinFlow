package com.coinflow.replay.client;

import com.coinflow.replay.client.dto.BinanceKline;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.retry.annotation.Backoff;
import org.springframework.retry.annotation.Retryable;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

@Component
public class BinanceKlineClient {

    private static final Logger log = LoggerFactory.getLogger(BinanceKlineClient.class);
    private static final String KLINE_API_PATH = "/api/v3/klines";

    private final RestTemplate restTemplate;
    private final String baseUrl;

    public BinanceKlineClient(RestTemplate restTemplate,
            @Value("${binance.api.base-url:https://api.binance.com}") String baseUrl) {
        this.restTemplate = restTemplate;
        this.baseUrl = baseUrl;
    }

    @Retryable(retryFor = { RestClientException.class }, noRetryFor = {
            HttpClientErrorException.class }, backoff = @Backoff(delay = 1000, multiplier = 2.0))
    public List<BinanceKline> fetchKlines(String symbol, String interval, long startTime, long endTime, int limit) {
        String url = UriComponentsBuilder.fromUriString(baseUrl)
                .path(KLINE_API_PATH)
                .queryParam("symbol", symbol.toUpperCase())
                .queryParam("interval", interval)
                .queryParam("startTime", startTime)
                .queryParam("endTime", endTime)
                .queryParam("limit", limit)
                .toUriString();

        log.debug("Fetching klines from Binance: {}", url);

        Object[][] rawResponse = restTemplate.getForObject(url, Object[][].class);

        if (rawResponse == null) {
            log.warn("Binance API returned null response for symbol: {}, interval: {}", symbol, interval);
            throw new IllegalStateException("Received null response from Binance API");
        }

        return Arrays.stream(rawResponse)
                .map(BinanceKline::fromArray)
                .collect(Collectors.toList());
    }
}
