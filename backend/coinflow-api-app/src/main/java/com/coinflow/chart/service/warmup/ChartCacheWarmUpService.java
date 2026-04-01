package com.coinflow.chart.service.warmup;

import com.coinflow.chart.service.OhlcChartService;
import com.coinflow.domain.ohlc.constant.OhlcInterval;
import com.coinflow.domain.symbol.domain.Symbol;
import com.coinflow.domain.symbol.service.SymbolService;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

/**
 * Service that warms up the chart cache at application startup.
 * Prevents Cold Start latency by pre-loading the last 1000 candles for all symbols.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(name = "chart.cache.warmup.enabled", havingValue = "true", matchIfMissing = true)
public class ChartCacheWarmUpService {

    private final SymbolService symbolService;
    private final OhlcChartService ohlcChartService;

    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady() {
        log.info("[CHART-WARMUP] Starting chart cache warm-up...");
        
        try {
            List<Symbol> symbols = symbolService.findAll();
            
            if (symbols.isEmpty()) {
                log.warn("[CHART-WARMUP] No active symbols found to warm up.");
                return;
            }

            for (Symbol symbol : symbols) {
                for (OhlcInterval interval : OhlcInterval.values()) {
                    ohlcChartService.warmUp(symbol, interval);
                }
            }
            log.info("[CHART-WARMUP] Chart cache warm-up completed successfully for {} symbols.", symbols.size());
        } catch (Exception e) {
            log.error("[CHART-WARMUP] Error occurred during chart cache warm-up", e);
        }
    }
}
