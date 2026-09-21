package com.coinflow.domain.recovery.repository;

import com.coinflow.domain.recovery.domain.FailedRecord;

import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface FailedRecordRepository extends JpaRepository<FailedRecord, String> {
    List<FailedRecord> findByStatusNotAndIdGreaterThanOrderByIdAsc(
            FailedRecord.Status status, String after, Pageable page);
}
