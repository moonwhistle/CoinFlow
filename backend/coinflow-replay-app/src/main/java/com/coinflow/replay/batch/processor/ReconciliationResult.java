package com.coinflow.replay.batch.processor;

import com.coinflow.domain.log.domain.MissingTickLog;
import com.coinflow.domain.ohlc.domain.Ohlc1m;

/**
 * 캔들 보정 결과와 로그를 함께 담는 래퍼 레코드
 */
public record ReconciliationResult(
        Ohlc1m ohlc1m,
        MissingTickLog missingTickLog) {
}
