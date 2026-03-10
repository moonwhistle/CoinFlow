package com.coinflow.replay.batch.processor;

import com.coinflow.domain.log.domain.MissingTickLog;
import com.coinflow.domain.ohlc.domain.Ohlc1m;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 캔들 보정 결과와 로그를 함께 담는 래퍼 클래스
 */
@Getter
@RequiredArgsConstructor
public class ReconciliationResult {
    private final Ohlc1m ohlc1m;
    private final MissingTickLog missingTickLog;
}
