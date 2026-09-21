package com.coinflow.recovery.redis;

import com.coinflow.domain.recovery.service.RecoveryMaintenanceService;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Shared scheduling adapter, enabled only in consumer and replay applications. */
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "coinflow.recovery.maintenance.enabled", havingValue = "true")
public class RecoveryMaintenanceWorker {
    private final RecoveryMaintenanceService maintenance;

    @Scheduled(fixedDelayString = "${coinflow.recovery.maintenance.interval-ms:5000}", initialDelay = 10000)
    public void maintain() {
        maintenance.maintain();
    }

    public boolean cleanCheckpointPending() {
        return maintenance.cleanCheckpointPending();
    }
}
