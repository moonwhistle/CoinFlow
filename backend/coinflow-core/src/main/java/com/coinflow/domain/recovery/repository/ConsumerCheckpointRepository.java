package com.coinflow.domain.recovery.repository;

import com.coinflow.domain.recovery.domain.ConsumerCheckpoint;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.*;

public interface ConsumerCheckpointRepository extends JpaRepository<ConsumerCheckpoint, Long> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from ConsumerCheckpoint c where c.id = 1")
    ConsumerCheckpoint lock();
}
