package com.coinflow.replay.client;

import com.coinflow.replay.client.dto.BinanceKline;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.retry.annotation.Backoff;
import org.springframework.retry.annotation.Retryable;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

@Component
public class BinanceKlineClient {

    private static final Logger log = LoggerFactory.getLogger(BinanceKlineClient.class);
    private final RestTemplate restTemplate;
    private final String baseUrl;

    public BinanceKlineClient(RestTemplate restTemplate,
            @Value("${binance.api.base-url:https://api.binance.com}") String baseUrl) {
        this.restTemplate = restTemplate;
        this.baseUrl = baseUrl;
    }

    @Retryable(maxAttempts = 3, backoff = @Backoff(delay = 1000, multiplier = 2.0))
    public List<BinanceKline> fetchKlines(String symbol, String interval, long startTime, long endTime, int limit) {
        String url = UriComponentsBuilder.fromUriString(baseUrl)
                .path("/api/v3/klines")
                .queryParam("symbol", symbol.toUpperCase())
                .queryParam("interval", interval)
                .queryParam("startTime", startTime)
                .queryParam("endTime", endTime)
                .queryParam("limit", limit)
                .toUriString();

        log.debug("Fetching klines from Binance: {}", url);

        Object[][] rawResponse = restTemplate.getForObject(url, Object[][].class);
        List<BinanceKline> result = new ArrayList<>();

        if (rawResponse != null) {
            for (Object[] rawKline : rawResponse) {
                result.add(new BinanceKline(
                        ((Number) rawKline[0]).longValue(),
                        new BigDecimal(rawKline[1].toString()),
                        new BigDecimal(rawKline[2].toString()),
                        new BigDecimal(rawKline[3].toString()),
                        new BigDecimal(rawKline[4].toString()),
                        new BigDecimal(rawKline[5].toString()),
                        ((Number) rawKline[6]).longValue(),
                        new BigDecimal(rawKline[7].toString()),
                        ((Number) rawKline[8]).intValue(),
                        new BigDecimal(rawKline[9].toString()),
                        new BigDecimal(rawKline[10].toString())));
            }
        }
        return result;
    }
}
