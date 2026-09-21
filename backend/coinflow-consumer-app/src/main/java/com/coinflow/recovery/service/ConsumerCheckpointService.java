package com.coinflow.recovery.service;

import com.coinflow.domain.recovery.domain.StreamRecordIds;
import com.coinflow.config.properties.TickConsumerProperties;
import com.coinflow.domain.aggregation.domain.vo.ClosedKlineSnapshot;
import com.coinflow.domain.aggregation.service.KlineAggregatorService;
import com.coinflow.domain.recovery.domain.ConsumerCheckpoint;
import com.coinflow.domain.recovery.service.RecoveryCandleStore;
import com.coinflow.domain.recovery.service.RecoveryLedger;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** Candle values, aggregate state and source offset commit in one DB transaction. */
@Service
@RequiredArgsConstructor
public class ConsumerCheckpointService {
    private static final long LEASE_MS = 30_000;
    private final RecoveryLedger ledger;
    private final RecoveryCandleStore candles;
    private final ObjectMapper mapper;
    private final TickConsumerProperties properties;
    private final String owner = UUID.randomUUID().toString();

    public record State(int version, KlineAggregatorService.Checkpoint aggregate, Map<String, Long> dedupe) {}
    public record Restored(String recordId, State state) {}
    public record Candle(String symbol, ClosedKlineSnapshot value) {}

    public Restored acquire() {
        return ledger.locked(checkpoint -> {
            long now = System.currentTimeMillis();
            if (checkpoint.getOwner() != null && checkpoint.getLeaseUntil() > now
                    && !owner.equals(checkpoint.getOwner())) {
                throw new IllegalStateException("Another aggregation consumer owns the checkpoint lease");
            }
            if (checkpoint.getStreamKey() != null && (!properties.streamKey().equals(checkpoint.getStreamKey())
                    || !properties.group().equals(checkpoint.getConsumerGroup()))) {
                throw new IllegalStateException("Checkpoint belongs to another stream/group");
            }
            checkpoint.setStreamKey(properties.streamKey());
            checkpoint.setConsumerGroup(properties.group());
            checkpoint.setOwner(owner);
            checkpoint.setLeaseUntil(now + LEASE_MS);
            return new Restored(checkpoint.getRecordId(), decode(checkpoint.getSnapshot()));
        });
    }

    public void commit(String recordId, State state, List<Candle> updates) {
        String json;
        try { json = mapper.writeValueAsString(state); }
        catch (Exception e) { throw new IllegalStateException("Cannot serialize checkpoint", e); }
        ledger.locked(checkpoint -> {
            renew(checkpoint);
            if (StreamRecordIds.compare(recordId, checkpoint.getRecordId()) < 0) {
                throw new IllegalStateException("Checkpoint regression rejected");
            }
            for (Candle update : updates) candles.saveConsumer(update.symbol(), update.value());
            checkpoint.setSnapshot(json);
            checkpoint.setRecordId(recordId);
            return null;
        });
    }

    public void heartbeat() {
        ledger.locked(checkpoint -> { renew(checkpoint); return null; });
    }

    public void release() {
        ledger.locked(checkpoint -> {
            if (owner.equals(checkpoint.getOwner())) {
                checkpoint.setOwner(null);
                checkpoint.setLeaseUntil(0);
            }
            return null;
        });
    }

    private void renew(ConsumerCheckpoint checkpoint) {
        long now = System.currentTimeMillis();
        if (!owner.equals(checkpoint.getOwner()) || checkpoint.getLeaseUntil() <= now) {
            throw new IllegalStateException("Aggregation checkpoint lease lost; restart required");
        }
        checkpoint.setLeaseUntil(now + LEASE_MS);
    }

    private State decode(String json) {
        if (json == null) return null;
        try {
            State state = mapper.readValue(json, State.class);
            if (state.version() != 1) throw new IllegalStateException("Unsupported checkpoint version");
            return state;
        } catch (Exception e) { throw new IllegalStateException("Cannot restore checkpoint", e); }
    }
}
