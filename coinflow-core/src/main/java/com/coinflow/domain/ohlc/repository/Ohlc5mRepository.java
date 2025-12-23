package com.coinflow.domain.ohlc.repository;

import com.coinflow.domain.ohlc.domain.Ohlc5m;
import java.time.LocalDateTime;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface Ohlc5mRepository extends JpaRepository<Ohlc5m, Long> {

    Optional<Ohlc5m> findBySymbolIdAndBucketTime(Long symbolId, LocalDateTime bucketTime);
}
