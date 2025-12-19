package com.coinflow.domain.ohlc.repository;

import com.coinflow.domain.ohlc.domain.Ohlc1m;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface Ohlc1mRepository extends JpaRepository<Ohlc1m, Long> {

    @Query("""
            select o
            from Ohlc1m o
            where o.bucketTime >= :start
              and o.bucketTime < :end
            """)
    List<Ohlc1m> findCandlesInBucketRange(LocalDateTime start, LocalDateTime end);
}
