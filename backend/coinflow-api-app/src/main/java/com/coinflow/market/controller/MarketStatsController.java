package com.coinflow.market.controller;

import com.coinflow.market.controller.response.MarketStats24hResponse;
import com.coinflow.market.service.MarketStatsService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
public class MarketStatsController {

    public static final String MARKET_STATS_24H_PATH = "/api/v1/market/{symbolId}/stats/24h";

    private final MarketStatsService marketStatsService;

    @GetMapping(MARKET_STATS_24H_PATH)
    public ResponseEntity<MarketStats24hResponse> show24hStats(@PathVariable Long symbolId) {
        return marketStatsService.get24hStats(symbolId)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.noContent().build());
    }
}
