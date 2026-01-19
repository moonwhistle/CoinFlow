package com.coinflow.domain.ohlc.repository;

import com.coinflow.domain.ohlc.domain.Ohlc1m;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface Ohlc1mRepository extends JpaRepository<Ohlc1m, Long> {

    @Query("""
            select o
            from Ohlc1m o
            where o.symbol.id = :symbolId
              and o.bucketTime >= :start
              and o.bucketTime < :end
            order by o.bucketTime asc
            """)
    List<Ohlc1m> findCandlesInBucketRange(
            @Param("symbolId") Long symbolId,
            @Param("start") LocalDateTime start,
            @Param("end") LocalDateTime end
    );

    Optional<Ohlc1m> findBySymbolIdAndBucketTime(Long symbolId, LocalDateTime bucketTime);
}
