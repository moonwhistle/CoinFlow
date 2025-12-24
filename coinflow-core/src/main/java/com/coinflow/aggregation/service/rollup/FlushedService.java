package com.coinflow.aggregation.service.rollup;

import com.coinflow.aggregation.event.Ohlc1mFlushedEvent;

/**
 * 1분봉(OHLC 1m) flush 완료 이벤트를 처리하는 서비스 계약.
 *
 * <p>구현체는 flush된 1분봉을 기준으로
 * 상위 interval(5m, 30m 등)의 롤업 트리거를 담당한다.
 */
public interface FlushedService {

    /**
     * 1분봉 flush 완료 시 호출된다.
     */
    void onOhlc1mFlushed(Ohlc1mFlushedEvent event);
}
