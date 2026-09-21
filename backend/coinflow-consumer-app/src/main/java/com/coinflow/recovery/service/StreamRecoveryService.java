package com.coinflow.recovery.service;

import com.coinflow.domain.recovery.repository.FailedRecordRepository;
import com.coinflow.domain.recovery.domain.FailedRecord;
import org.springframework.beans.factory.ObjectProvider;
import com.coinflow.recovery.redis.RecoveryMaintenanceWorker;
import java.util.HashSet;
import com.coinflow.aggregation.service.TickProcessService;
import com.coinflow.config.properties.TickConsumerProperties;
import com.coinflow.domain.recovery.domain.StreamRecordIds;
import com.coinflow.handler.TickRawMessageHandler;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.connection.Limit;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@Slf4j
public class StreamRecoveryService {
    private final RedisTemplate<String, byte[]> rawRedisTemplate;
    private final TickConsumerProperties properties;
    private final TickProcessService ticks;
    private final TickRawMessageHandler handler;
    private final FailedRecordRepository failures;
    private final FailedRecordService failedRecords;
    private final ObjectProvider<RecoveryMaintenanceWorker> maintenance;
    @Value("${coinflow.recovery.allow-initial-replay:false}") private boolean allowInitialReplay;

    public void recover() {
        String checkpoint = ticks.restore();
        maintenance.ifAvailable(worker -> {
            while (worker.cleanCheckpointPending()) ticks.flush();
        });
        var streams = rawRedisTemplate.<String, byte[]>opsForStream();
        String highWater = streams.groups(properties.streamKey()).stream()
                .filter(g -> g.groupName().equals(properties.group())).findFirst().orElseThrow()
                .lastDeliveredId();
        if (checkpoint.equals("0-0") && !highWater.equals("0-0") && !allowInitialReplay) {
            throw new IllegalStateException("RECOVERY_BASELINE_REQUIRED: existing group has no checkpoint; see recovery runbook");
        }
        if (!checkpoint.equals("0-0") && streams.range(properties.streamKey(), Range.closed(checkpoint, checkpoint)).isEmpty()) {
            throw new IllegalStateException("RECOVERY_HISTORY_MISSING: checkpoint anchor was trimmed or Redis was reset");
        }
        // Includes previously ACKed records. XAUTOCLAIM alone cannot rebuild lost in-memory state.
        String cursor = checkpoint;
        while (StreamRecordIds.compare(cursor, highWater) < 0) {
            List<MapRecord<String, String, byte[]>> page = streams.range(properties.streamKey(),
                    Range.of(Range.Bound.exclusive(cursor), Range.Bound.inclusive(highWater)), Limit.limit().count(500));
            if (page == null || page.isEmpty()) throw new IllegalStateException("RECOVERY_HISTORY_MISSING before " + highWater);
            var recorded = new HashSet<String>();
            failures.findAllById(page.stream().map(record -> FailedRecord.key(
                    properties.streamKey(), properties.group(), record.getId().getValue())).toList())
                    .forEach(failure -> {
                        ticks.flush();
                        failedRecords.resume(failure);
                        recorded.add(failure.getRecordId());
                    });
            for (var record : page) {
                if (!recorded.contains(record.getId().getValue())) {
                    handler.handle(record.getValue(), properties.streamKey(), properties.group(), record.getId());
                }
                cursor = record.getId().getValue();
            }
            ticks.flush();
        }
        ticks.flush();
        log.info("RECOVERY_COMPLETED checkpoint={}, replayThrough={}", checkpoint, highWater);
    }
}
