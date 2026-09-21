package com.coinflow.domain.recovery.service;

import com.coinflow.domain.recovery.domain.ConsumerCheckpoint;
import com.coinflow.domain.recovery.repository.ConsumerCheckpointRepository;

import java.util.function.Function;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** All consumer writes and authoritative repairs take this DB lock first. */
@Service
public class RecoveryLedger {
    private final ConsumerCheckpointRepository checkpoints;
    private final TransactionTemplate transaction;
    private final TransactionTemplate initialization;
    private volatile boolean initialized;

    public RecoveryLedger(ConsumerCheckpointRepository checkpoints, PlatformTransactionManager manager) {
        this.checkpoints = checkpoints;
        transaction = new TransactionTemplate(manager);
        initialization = new TransactionTemplate(manager);
        initialization.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public synchronized void initialize() {
        if (initialized) return;
        if (!Boolean.TRUE.equals(initialization.execute(s -> checkpoints.existsById(1L)))) {
            try {
                initialization.executeWithoutResult(s -> checkpoints.saveAndFlush(new ConsumerCheckpoint()));
            } catch (DataIntegrityViolationException concurrentInitializer) {
                if (!Boolean.TRUE.equals(initialization.execute(s -> checkpoints.existsById(1L)))) throw concurrentInitializer;
            }
        }
        initialized = true;
    }

    public <T> T locked(Function<ConsumerCheckpoint, T> action) {
        initialize();
        return transaction.execute(status -> action.apply(checkpoints.lock()));
    }
}
