package com.coinflow.domain.ohlc.repository;

import com.coinflow.domain.ohlc.domain.Ohlc1m;
import com.coinflow.domain.symbol.domain.Symbol;
import jakarta.persistence.LockModeType;
import java.time.LocalDateTime;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface Ohlc1mRepository extends JpaRepository<Ohlc1m, Long> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select o
            from Ohlc1m o
            where o.symbol = :symbol
              and o.bucketTime = :bucketTime
            """)
    Optional<Ohlc1m> findForUpdate(@Param("symbol") Symbol symbol, @Param("bucketTime") LocalDateTime bucketTime);
}
