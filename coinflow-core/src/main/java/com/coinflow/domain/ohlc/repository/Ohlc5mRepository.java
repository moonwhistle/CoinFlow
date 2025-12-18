package com.coinflow.domain.ohlc.repository;

import com.coinflow.domain.ohlc.domain.Ohlc5m;
import com.coinflow.domain.symbol.domain.Symbol;
import java.time.LocalDateTime;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface Ohlc5mRepository extends JpaRepository<Ohlc5m, Long> {

    Optional<Ohlc5m> findBySymbolAndBucketTime(Symbol symbol, LocalDateTime bucketTime);
}
