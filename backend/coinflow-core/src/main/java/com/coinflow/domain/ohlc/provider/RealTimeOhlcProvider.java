package com.coinflow.domain.ohlc.provider;

import com.coinflow.domain.ohlc.domain.Ohlc1m;
import java.time.LocalDateTime;
import java.util.Optional;

public interface RealTimeOhlcProvider {
    /**
     * 현재 진행 중인 버킷(In-Memory)의 OHLC 데이터를 조회한다.
     *
     * @param symbolId   심볼 ID
     * @param bucketTime 버킷 시작 시간
     * @return 진행 중인 OHLC 데이터 (존재하지 않으면 Empty)
     */
    Optional<Ohlc1m> getRealTimeCandle(Long symbolId, LocalDateTime bucketTime);
}
