package com.coinflow.aggregation.service.rollup.upserter;

import com.coinflow.aggregation.process.rollup.OhlcRollup;
import com.coinflow.domain.ohlc.constant.OhlcInterval;
import com.coinflow.domain.symbol.domain.Symbol;
import java.time.LocalDateTime;

/**
 * Interval 별 OHLC 롤업 결과를 저장하는 전략 인터페이스.
 *
 * <p>각 구현체는 하나의 {@link OhlcInterval}에 대해서만 책임을 가지며,
 * 집계 계산이 아닌 저장(upsert)만 담당한다.
 */
public interface OhlcRollupUpserter {

    /**
     * 이 Upserter가 처리하는 OHLC interval.
     */
    OhlcInterval supports();

    /**
     * 집계된 OHLC 롤업 결과를 해당 interval 저장소에 upsert 한다.
     */
    void upsert(Long symbolId, Symbol symbol, LocalDateTime bucketTime, OhlcRollup rollup);
}
