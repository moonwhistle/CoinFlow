package com.coinflow.process.rollup;

import static com.coinflow.common.exception.CoreErrorCode.ROLLUP_TIME_RANGE_ERROR;

import com.coinflow.common.exception.CoreException;
import com.coinflow.domain.ohlc.domain.OhlcCandle;
import com.coinflow.domain.ohlc.constant.OhlcInterval;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public final class OhlcRollupCalculator {

    private OhlcRollupCalculator() {
    }

    public static <T extends OhlcCandle> Optional<OhlcRollup> tryRollup(List<T> candles, OhlcInterval targetInterval,
                                                                        LocalDateTime bucketStart) {
        if (!canRollup(candles, targetInterval, bucketStart)) {
            return Optional.empty();
        }

        validateRange(candles, targetInterval, bucketStart);

        return Optional.of(calculate(candles));
    }

    /**
     * 버킷 종료 판단
     */
    private static <T extends OhlcCandle> boolean canRollup(List<T> candles, OhlcInterval targetInterval,
                                                            LocalDateTime bucketStart) {
        if (candles == null || candles.isEmpty() || targetInterval == null || bucketStart == null) {
            return false;
        }

        LocalDateTime lastMinute = bucketStart.plus(targetInterval.duration()).minusMinutes(1);

        return candles.stream()
                .anyMatch(c -> c.getBucketTime().equals(lastMinute));
    }

    private static <T extends OhlcCandle> void validateRange(List<T> candles, OhlcInterval targetInterval,
                                                             LocalDateTime bucketStart) {
        if (targetInterval == null || bucketStart == null) {
            throw new CoreException(ROLLUP_TIME_RANGE_ERROR);
        }

        LocalDateTime bucketEnd = bucketStart.plus(targetInterval.duration());

        for (OhlcCandle c : candles) {
            LocalDateTime t = c.getBucketTime();
            if (t.isBefore(bucketStart) || !t.isBefore(bucketEnd)) {
                throw new CoreException(ROLLUP_TIME_RANGE_ERROR);
            }
        }
    }

    /**
     * @param sorted bucketTime 기준 오름차순 정렬된 데이터
     */
    private static <T extends OhlcCandle> OhlcRollup calculate(List<T> sorted) {
        BigDecimal open = sorted.get(0).getOpenPrice();
        BigDecimal close = sorted.get(sorted.size() - 1).getClosePrice();

        BigDecimal high = sorted.stream()
                .map(OhlcCandle::getHighPrice)
                .max(BigDecimal::compareTo)
                .orElse(open);

        BigDecimal low = sorted.stream()
                .map(OhlcCandle::getLowPrice)
                .min(BigDecimal::compareTo)
                .orElse(open);

        long volume = sorted.stream()
                .mapToLong(OhlcCandle::getVolume)
                .reduce(0L, Math::addExact);

        return new OhlcRollup(open, high, low, close, volume);
    }
}
