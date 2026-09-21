package com.coinflow.recovery.service;

import com.coinflow.aggregation.service.TickProcessService;
import com.coinflow.domain.recovery.domain.FailedRecord;
import com.coinflow.domain.recovery.repository.FailedRecordRepository;
import com.coinflow.domain.recovery.service.RecoveryLedger;
import com.coinflow.recovery.redis.RedisDeadLetterStore;
import java.util.Base64;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@Slf4j
public class FailedRecordService {
    private final RecoveryLedger ledger;
    private final FailedRecordRepository failures;
    private final RedisDeadLetterStore dlq;
    private final TickProcessService ticks;
    @Value("${coinflow.recovery.dlq-backoff-ms:1000}") private long backoffMs = 1000;

    public void resume(FailedRecord failure) {
        if (failure.getDlqId() == null && !failure.isDlqExhausted() && failure.getStatus() == FailedRecord.Status.WAITING_REPAIR) {
            publish(failure);
        }
    }

    public void record(String stream, String group, String id, byte[] payload,
            String symbol, Long eventTime, RuntimeException cause) {
        // Freeze earlier successful ticks before tracking a skipped record.
        ticks.flush();
        Set<String> required = symbol != null && eventTime != null && eventTime > 0
                ? ticks.affectedCandles(symbol, eventTime) : Set.of();
        String key = FailedRecord.key(stream, group, id);
        FailedRecord failure = ledger.locked(checkpoint -> failures.findById(key).orElseGet(() -> {
            FailedRecord created = new FailedRecord();
            created.setId(key);
            created.setStreamKey(stream);
            created.setConsumerGroup(group);
            created.setRecordId(id);
            created.setSymbol(symbol);
            created.setPayload(Base64.getEncoder().encodeToString(payload == null ? new byte[0] : payload));
            created.setRequiredCandles(String.join(",", required));
            String reason = cause.getClass().getSimpleName() + ": " + cause.getMessage();
            created.setReason(reason.substring(0, Math.min(2000, reason.length())));
            created.setCreatedAt(System.currentTimeMillis());
            return failures.save(created);
        }));
        publish(failure);
    }

    private void publish(FailedRecord failure) {
        String failureKey = failure.getId();
        while (failure.getDlqAttempts() < 3 && failure.getDlqId() == null) {
            if (failure.getDlqAttempts() > 0) pause(backoffMs * (1L << (failure.getDlqAttempts() - 1)));
            failure = ledger.locked(checkpoint -> {
                FailedRecord current = failures.findById(failureKey).orElseThrow();
                current.setDlqAttempts(current.getDlqAttempts() + 1);
                return current;
            });
            try {
                String dlqId = dlq.publishAndAcknowledge(failure);
                String key = failure.getId();
                ledger.locked(checkpoint -> {
                    failures.findById(key).orElseThrow().setDlqId(dlqId);
                    return null;
                });
                return;
            } catch (RuntimeException error) {
                log.warn("DLQ_PUBLISH_FAILED record={}, attempt={}", failure.getRecordId(), failure.getDlqAttempts(), error);
            }
        }
        String key = failure.getId();
        ledger.locked(checkpoint -> {
            failures.findById(key).orElseThrow().setDlqExhausted(true);
            return null;
        });
        log.error("DLQ_PUBLISH_EXHAUSTED stream={}, group={}, recordId={}; preserve PEL until verified repair",
                failure.getStreamKey(), failure.getConsumerGroup(), failure.getRecordId());
    }

    private void pause(long delay) {
        try { Thread.sleep(delay + (delay == 0 ? 0 : ThreadLocalRandom.current().nextLong(Math.max(1, delay / 5)))); }
        catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted DLQ retry; preserve PEL", e);
        }
    }
}
