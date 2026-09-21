package com.coinflow.domain.recovery.service;

import com.coinflow.domain.ohlc.constant.OhlcWindowPolicy;
import com.coinflow.domain.ohlc.repository.OhlcWindowRepository;
import com.coinflow.domain.ohlc.service.Ohlc1mService;
import com.coinflow.domain.ohlc.service.Ohlc5mService;
import com.coinflow.domain.ohlc.service.Ohlc30mService;
import com.coinflow.domain.ohlc.snapshot.OhlcCandleSnapshot;
import com.coinflow.domain.recovery.domain.FailedRecord;
import com.coinflow.domain.recovery.domain.StreamRecordIds;
import com.coinflow.domain.recovery.repository.FailedRecordRepository;
import com.coinflow.domain.recovery.repository.VerifiedCandleRepository;
import com.coinflow.domain.recovery.service.RecoveryLedger;
import com.coinflow.domain.symbol.service.SymbolService;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import com.coinflow.domain.recovery.repository.RecoveryStreamRepository;

/** Bounded sweeps, also runs in replay-app while the consumer is down. No tick re-aggregation. */
@Service
@RequiredArgsConstructor
@Slf4j
public class RecoveryMaintenanceService {
    private final RecoveryLedger ledger;
    private final FailedRecordRepository failures;
    private final VerifiedCandleRepository verified;
    private final RecoveryStreamRepository streams;
    private final OhlcWindowRepository window;
    private final SymbolService symbols;
    private final Ohlc1mService minute;
    private final Ohlc5mService five;
    private final Ohlc30mService thirty;
    private String pendingCursor = "0-0";
    private String failureCursor = "";
    public synchronized void maintain() {
        try {
            resolveFailures();
            cleanCheckpointPending();
            publishRepairs();
            trimRecoverableHistory();
        } catch (RuntimeException e) {
            log.error("RECOVERY_MAINTENANCE_FAILED: retained state will be retried", e);
        }
    }

    public void resolveFailures() {
        ledger.locked(checkpoint -> {
            var page = failures.findByStatusNotAndIdGreaterThanOrderByIdAsc(
                    FailedRecord.Status.RESOLVED, failureCursor, PageRequest.of(0, 100));
            for (FailedRecord failure : page) {
                String required = failure.getRequiredCandles();
                if (required != null && !required.isBlank()
                        && Arrays.stream(required.split(",")).allMatch(verified::existsById)) {
                    failure.setStatus(FailedRecord.Status.ACK_PENDING);
                    try {
                        streams.acknowledge(failure.getStreamKey(), failure.getConsumerGroup(), List.of(failure.getRecordId()));
                        failure.setStatus(FailedRecord.Status.RESOLVED);
                    } catch (RuntimeException e) {
                        log.warn("REPAIR_ACK_PENDING record={}", failure.getRecordId(), e);
                    }
                }
                failureCursor = failure.getId();
            }
            if (page.size() < 100) failureCursor = "";
            return null;
        });
    }

    public boolean cleanCheckpointPending() {
        return ledger.locked(checkpoint -> {
            if (checkpoint.getStreamKey() == null || checkpoint.getRecordId().equals("0-0")) return false;
            var page = streams.pending(checkpoint.getStreamKey(), checkpoint.getConsumerGroup(), pendingCursor, checkpoint.getRecordId(), 500);
            List<String> keys = new ArrayList<>();
            page.forEach(p -> keys.add(FailedRecord.key(checkpoint.getStreamKey(), checkpoint.getConsumerGroup(), p)));
            Map<String, FailedRecord> known = new HashMap<>();
            failures.findAllById(keys).forEach(f -> known.put(f.getRecordId(), f));
            List<String> safe = new ArrayList<>();
            page.forEach(p -> {
                FailedRecord failure = known.get(p);
                if (failure == null || failure.getDlqId() != null || failure.getStatus() != FailedRecord.Status.WAITING_REPAIR) {
                    safe.add(p);
                }
            });
            if (!safe.isEmpty()) streams.acknowledge(checkpoint.getStreamKey(), checkpoint.getConsumerGroup(), safe);
            page.forEach(p -> pendingCursor = p);
            if (page.size() < 500) pendingCursor = "0-0";
            return page.size() == 500;
        });
    }

    public void publishRepairs() {
        ledger.locked(checkpoint -> {
            for (var marker : verified.findTop100ByCachePublishedFalseOrderByVerifiedAtAsc()) {
                var symbol = symbols.findBySymbol(marker.getSymbol());
                var time = LocalDateTime.ofEpochSecond(marker.getBucket(), 0, ZoneOffset.UTC);
                var candle = switch (marker.getIntervalName()) {
                    case "M1" -> minute.findBySymbolIdAndBucketTime(symbol.getId(), time).orElseThrow();
                    case "M5" -> five.findBySymbolIdAndBucketTime(symbol.getId(), time).orElseThrow();
                    case "M30" -> thirty.findBySymbolIdAndBucketTime(symbol.getId(), time).orElseThrow();
                    default -> throw new IllegalStateException("Unknown repaired interval");
                };
                window.save(marker.getSymbol(), marker.getIntervalName(), OhlcCandleSnapshot.from(candle));
                window.trim(marker.getSymbol(), marker.getIntervalName(), OhlcWindowPolicy.MAX_SIZE);
                streams.clearLive(marker.getSymbol(), marker.getIntervalName(), marker.getBucket());
                marker.setCachePublished(true);
            }
            return null;
        });
    }

    public void trimRecoverableHistory() {
        ledger.locked(checkpoint -> {
            if (checkpoint.getStreamKey() == null || checkpoint.getRecordId().equals("0-0")) return null;
            // Never trim history needed by an unrelated consumer group.
            if (!streams.hasSingleGroup(checkpoint.getStreamKey())) return null;
            String floor = checkpoint.getRecordId();
            var oldest = streams.pending(checkpoint.getStreamKey(), checkpoint.getConsumerGroup(), "0-0", null, 1);
            for (var message : oldest) if (StreamRecordIds.compare(message, floor) < 0) floor = message;
            streams.trimBefore(checkpoint.getStreamKey(), floor);
            return null;
        });
    }
}
