package com.coinflow.aggregation.infrastructure.persistence;

import com.coinflow.domain.aggregation.domain.vo.ClosedKlineSnapshot;
import com.coinflow.domain.recovery.service.RecoveryCandleStore;
import java.util.concurrent.CompletableFuture;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.retry.annotation.Backoff;
import org.springframework.retry.annotation.Recover;
import org.springframework.retry.annotation.Retryable;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

/**
 * Legacy async adapter retained for existing callers/benchmarks.
 * The live consumer commits through ConsumerCheckpointService instead.
 * Both paths must obey the authoritative repair fence.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DbPersistService {
    private final RecoveryCandleStore candles;

    @Async("dbPersistExecutor")
    @Retryable(retryFor = Exception.class,
            maxAttemptsExpression = "${coinflow.async.db-persist.max-attempts}",
            backoff = @Backoff(delayExpression = "${coinflow.async.db-persist.retry-delay}",
                    multiplierExpression = "${coinflow.async.db-persist.retry-multiplier}"))
    public CompletableFuture<Void> persistClosedCandleAsync(String symbol, ClosedKlineSnapshot snapshot) {
        candles.saveConsumer(symbol, snapshot);
        return CompletableFuture.completedFuture(null);
    }

    @Recover
    public CompletableFuture<Void> recover(Exception error, String symbol, ClosedKlineSnapshot snapshot) {
        log.error("Legacy candle persistence exhausted retries: symbol={}, interval={}", symbol, snapshot.interval(), error);
        return CompletableFuture.failedFuture(error);
    }
}
