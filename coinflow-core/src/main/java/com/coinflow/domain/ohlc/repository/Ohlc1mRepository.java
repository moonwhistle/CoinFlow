package com.coinflow.domain.ohlc.repository;

import com.coinflow.domain.ohlc.domain.Ohlc1m;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

public interface Ohlc1mRepository extends JpaRepository<Ohlc1m, Long> {

    @EntityGraph(attributePaths = "symbol")
    List<Ohlc1m> findAllByBucketTimeBetween(LocalDateTime startInclusive, LocalDateTime endExclusive);
}
