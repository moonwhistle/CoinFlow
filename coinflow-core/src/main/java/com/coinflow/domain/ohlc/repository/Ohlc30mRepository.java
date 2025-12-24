package com.coinflow.domain.ohlc.repository;

import com.coinflow.domain.ohlc.domain.Ohlc30m;
import java.time.LocalDateTime;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface Ohlc30mRepository extends JpaRepository<Ohlc30m, Long> {

    Optional<Ohlc30m> findBySymbolIdAndBucketTime(Long symbolId, LocalDateTime bucketTime);
}
