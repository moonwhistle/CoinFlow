package com.coinflow.aggregation.service.rollup;

import com.coinflow.aggregation.service.rollup.executor.OhlcRollupExecutor;
import com.coinflow.domain.ohlc.constant.OhlcInterval;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class Ohlc30mRollupService {

    private static final OhlcInterval INTERVAL = OhlcInterval.M30;

    private final OhlcRollupExecutor rollupExecutor;

    /**
     * 1분 봉 flush 이벤트(버킷 시작 시각)가 도착했을 때,
     * 해당 1분 봉이 속한 30분 버킷과 직전 30분 버킷을 재계산한다.
     *
     * <p>직전 버킷을 포함하는 이유: 지연 도착한 1분 봉이 직전 30분 버킷에 속할 수 있기 때문.</p>
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void rollupInNewTransaction(Long symbolId, LocalDateTime bucketStart1m) {
        LocalDateTime bucketStart30m = INTERVAL.resolveBucketStart(bucketStart1m);
        rollupExecutor.rollupFrom1mIfClosed(symbolId, INTERVAL, bucketStart30m);
        rollupExecutor.rollupFrom1mIfClosed(symbolId, INTERVAL, bucketStart30m.minus(INTERVAL.duration()));
    }
}
