package com.coinflow.domain.log.repository;

import com.coinflow.domain.log.domain.MissingTickLog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface MissingTickLogRepository extends JpaRepository<MissingTickLog, Long> {
}
