package com.coinflow.aggregation.service.rollup;

import com.coinflow.aggregation.service.rollup.executor.OhlcRollupExecutor;
import com.coinflow.domain.ohlc.constant.OhlcInterval;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Slf4j
public class Ohlc5mRollupService {

    private static final OhlcInterval INTERVAL = OhlcInterval.M5;

    private final OhlcRollupExecutor rollupExecutor;

    /**
     * 1분 봉 flush 이벤트(버킷 시작 시각)가 도착했을 때,
     * 해당 1분 봉이 속한 5분 버킷과 직전 5분 버킷을 재계산한다.
     * <p>
     * - 현재 버킷: 정상적으로 닫혔을 때 rollup 수행
     * - 직전 버킷: 지연 도착(늦게 flush 된 1분 봉)이 직전 버킷에 영향을 줄 수 있어 재계산
     * </p>
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void rollupInNewTransaction(Long symbolId, LocalDateTime bucketStart1m) {
        log.info("[Rollup] Triggering 5m rollup for symbolId={}, 1mBucket={}", symbolId, bucketStart1m);
        LocalDateTime bucketStart5m = INTERVAL.resolveBucketStart(bucketStart1m);
        rollupExecutor.rollupFrom1mIfClosed(symbolId, INTERVAL, bucketStart5m);
        rollupExecutor.rollupFrom1mIfClosed(symbolId, INTERVAL, bucketStart5m.minus(INTERVAL.duration()));
    }
}
