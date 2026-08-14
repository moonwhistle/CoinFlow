package com.coinflow.aggregation.infrastructure.persistence;

import com.coinflow.domain.aggregation.domain.vo.ClosedKlineSnapshot;
import com.coinflow.domain.ohlc.constant.OhlcInterval;
import com.coinflow.domain.ohlc.policy.VolumeScaler;
import com.coinflow.domain.ohlc.service.Ohlc1mService;
import com.coinflow.domain.ohlc.service.Ohlc30mService;
import com.coinflow.domain.ohlc.service.Ohlc5mService;
import com.coinflow.domain.symbol.domain.Symbol;
import com.coinflow.domain.symbol.service.SymbolService;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.retry.annotation.Backoff;
import org.springframework.retry.annotation.Recover;
import org.springframework.retry.annotation.Retryable;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import java.util.concurrent.CompletableFuture;

/**
 * Infrastructure adapter for persistence.
 * Handles asynchronous DB storage with retry logic.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DbPersistService {

    private final SymbolService symbolService;
    private final Ohlc1mService ohlc1mService;
    private final Ohlc5mService ohlc5mService;
    private final Ohlc30mService ohlc30mService;

    /**
     * Persists candle data asynchronously to the database with retry logic.
     */
    @Async("dbPersistExecutor")
    @Retryable(
            retryFor = { Exception.class },
            maxAttemptsExpression = "${coinflow.async.db-persist.max-attempts}",
            backoff = @Backoff(
                    delayExpression = "${coinflow.async.db-persist.retry-delay}",
                    multiplierExpression = "${coinflow.async.db-persist.retry-multiplier}"
            )
    )
    public CompletableFuture<Void> persistClosedCandleAsync(String symbolCode, ClosedKlineSnapshot closedSnapshot) {
        log.debug("[DB-ASYNC] Started persistence for {} candle, symbol={}", closedSnapshot.interval(), symbolCode);

        Symbol symbol = symbolService.findBySymbol(symbolCode);
        LocalDateTime bucketTime = LocalDateTime.ofEpochSecond(
                closedSnapshot.snapshot().startTime(), 0, ZoneOffset.UTC);
        long volume = VolumeScaler.toLong(closedSnapshot.snapshot().volume());

        saveByInterval(symbol, bucketTime, closedSnapshot, volume);

        log.info("[DB-ASYNC] Successfully persisted {} candle for {} at {}",
                closedSnapshot.interval(), symbolCode, bucketTime);

        return CompletableFuture.completedFuture(null);
    }

    private void saveByInterval(Symbol symbol, LocalDateTime bucketTime, ClosedKlineSnapshot closedSnapshot, long volume) {
        String interval = closedSnapshot.interval();
        BigDecimal open = closedSnapshot.snapshot().open();
        BigDecimal high = closedSnapshot.snapshot().high();
        BigDecimal low = closedSnapshot.snapshot().low();
        BigDecimal close = closedSnapshot.snapshot().close();

        if (OhlcInterval.M1.name().equals(interval)) {
            ohlc1mService.applyAndSave(symbol, bucketTime, open, high, low, close, volume);
        } else if (OhlcInterval.M5.name().equals(interval)) {
            ohlc5mService.applyAndSave(symbol, bucketTime, open, high, low, close, volume);
        } else if (OhlcInterval.M30.name().equals(interval)) {
            ohlc30mService.applyAndSave(symbol, bucketTime, open, high, low, close, volume);
        } else {
            log.warn("Unknown interval for DB persistence: {}", interval);
        }
    }

    /**
     * Executes when all retry attempts fail.
     */
    @Recover
    public CompletableFuture<Void> recover(Exception e, String symbolCode, ClosedKlineSnapshot closedSnapshot) {
        log.error("[DB-ASYNC-FATAL] All retry attempts failed for {} {} candle. Message will remain pending. error={}",
                symbolCode, closedSnapshot.interval(), e.getMessage());

        return CompletableFuture.failedFuture(e);
    }
}
