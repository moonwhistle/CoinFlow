package com.coinflow.domain.recovery.repository;

import com.coinflow.domain.recovery.domain.VerifiedCandle;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface VerifiedCandleRepository extends JpaRepository<VerifiedCandle, String> {
    List<VerifiedCandle> findTop100ByCachePublishedFalseOrderByVerifiedAtAsc();
}
