package com.coinflow.domain.ohlc.repository;

import com.coinflow.domain.ohlc.domain.Ohlc30m;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface Ohlc30mRepository extends JpaRepository<Ohlc30m, Long> {

    Optional<Ohlc30m> findBySymbolIdAndBucketTime(Long symbolId, LocalDateTime bucketTime);

    @Query("""
            select o
            from Ohlc30m o
            where o.symbol.id = :symbolId
              and o.bucketTime >= :start
              and o.bucketTime < :end
            order by o.bucketTime asc
            """)
    List<Ohlc30m> findCandlesInBucketRange(
            @Param("symbolId") Long symbolId,
            @Param("start") LocalDateTime start,
            @Param("end") LocalDateTime end
    );
}
