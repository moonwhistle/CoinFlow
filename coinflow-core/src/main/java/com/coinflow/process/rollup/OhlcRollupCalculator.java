package com.coinflow.process.rollup;

import static com.coinflow.common.exception.CoreErrorCode.ROLLUP_INSUFFICIENT_SOURCE_DATA;
import static com.coinflow.common.exception.CoreErrorCode.ROLLUP_TIME_RANGE_ERROR;

import com.coinflow.common.exception.CoreException;
import com.coinflow.domain.ohlc.domain.OhlcCandle;
import com.coinflow.domain.rollup.domain.vo.OhlcInterval;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * OHLC 캔들(1m/5m/30m) 목록을 더 큰 버킷(5m/30m/1d)으로 롤업한다.
 */
public final class OhlcRollupCalculator {

    private OhlcRollupCalculator() {
    }

    public static <T extends OhlcCandle> OhlcRollup rollup(List<T> candles, OhlcInterval sourceInterval,
                                                           OhlcInterval targetInterval, LocalDateTime bucketStart) {
        validateInput(candles, sourceInterval, targetInterval, bucketStart);

        List<T> sorted = sortByBucketTime(candles);

        validateCandleCount(sorted, sourceInterval, targetInterval);
        validateBucketRange(sorted, targetInterval, bucketStart);

        return calculate(sorted);
    }

    private static <T extends OhlcCandle> void validateInput(List<T> candles, OhlcInterval sourceInterval,
                                                             OhlcInterval targetInterval, LocalDateTime bucketStart) {
        if (candles == null || candles.isEmpty()) {
            throw new CoreException(ROLLUP_INSUFFICIENT_SOURCE_DATA);
        }

        if (sourceInterval == null || targetInterval == null || bucketStart == null) {
            throw new CoreException(ROLLUP_TIME_RANGE_ERROR);
        }
    }

    private static <T extends OhlcCandle> void validateCandleCount(List<T> candles, OhlcInterval sourceInterval,
                                                                   OhlcInterval targetInterval) {
        int expected = targetInterval.requiredCandleCount(sourceInterval);

        if (candles.size() < expected) {
            throw new CoreException(ROLLUP_INSUFFICIENT_SOURCE_DATA);
        }
    }

    private static <T extends OhlcCandle> void validateBucketRange(List<T> candles, OhlcInterval targetInterval,
                                                                   LocalDateTime bucketStart) {
        LocalDateTime bucketEnd = bucketStart.plus(targetInterval.duration());

        for (OhlcCandle candle : candles) {
            LocalDateTime time = candle.getBucketTime();

            if (time == null || time.isBefore(bucketStart) || !time.isBefore(bucketEnd)) {
                throw new CoreException(ROLLUP_TIME_RANGE_ERROR);
            }
        }
    }

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
                .map(OhlcCandle::getVolume)
                .filter(Objects::nonNull)
                .mapToLong(Long::longValue)
                .sum();

        return new OhlcRollup(open, high, low, close, volume);
    }

    private static <T extends OhlcCandle> List<T> sortByBucketTime(List<T> candles) {
        return candles.stream()
                .sorted(Comparator.comparing(OhlcCandle::getBucketTime))
                .toList();
    }
}
