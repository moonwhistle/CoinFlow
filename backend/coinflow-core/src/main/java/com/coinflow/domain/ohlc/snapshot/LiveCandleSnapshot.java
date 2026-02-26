package com.coinflow.domain.ohlc.snapshot;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Redis에 저장되는 실시간 캔들 스냅샷 전용 DTO.
 * JPA Entity(Ohlc1m)와 분리하여 직렬화/역직렬화 문제를 원천 차단한다.
 *
 * @param symbolId     심볼 ID
 * @param symbolCode   심볼 코드 (e.g. "btcusdt")
 * @param bucketTime   버킷 시작 시간
 * @param open         시가
 * @param high         고가
 * @param low          저가
 * @param close        종가
 * @param volume       거래량 (VolumeScaler 적용된 long)
 * @param lastStreamId Consumer가 마지막으로 처리한 Redis Stream Record ID
 */
public record LiveCandleSnapshot(
        Long symbolId,
        String symbolCode,
        LocalDateTime bucketTime,
        BigDecimal open,
        BigDecimal high,
        BigDecimal low,
        BigDecimal close,
        long volume,
        String lastStreamId) {
}
