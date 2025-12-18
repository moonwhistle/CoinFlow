package com.coinflow.process.rollup;

import static com.coinflow.common.exception.CoreErrorCode.ROLLUP_TIME_RANGE_ERROR;

import com.coinflow.common.exception.CoreException;
import com.coinflow.domain.ohlc.domain.OhlcCandle;
import com.coinflow.domain.rollup.domain.vo.OhlcInterval;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

public final class OhlcRollupCalculator {

    private OhlcRollupCalculator() {}

    public static <T extends OhlcCandle> Optional<OhlcRollup> tryRollup(List<T> candles, OhlcInterval sourceInterval, OhlcInterval targetInterval, LocalDateTime bucketStart) {
        if (!isReady(candles, sourceInterval, targetInterval, bucketStart)) {
            return Optional.empty();
        }

        return Optional.of(calculate(sortedCopy(candles)));
    }

    private static <T extends OhlcCandle> boolean isReady(List<T> candles, OhlcInterval sourceInterval, OhlcInterval targetInterval, LocalDateTime bucketStart) {
        if (candles == null || candles.isEmpty()) {
            return false; // 아직 데이터 부족
        }
        if (sourceInterval == null || targetInterval == null || bucketStart == null) {
            throw new CoreException(ROLLUP_TIME_RANGE_ERROR);
        }

        int expected = targetInterval.requiredCandleCount(sourceInterval);
        if (candles.size() < expected) {
            return false; // 아직 미완성
        }

        LocalDateTime bucketEnd = bucketStart.plus(targetInterval.duration());
        for (OhlcCandle c : candles) {
            LocalDateTime t = c.getBucketTime();

            if (t.isBefore(bucketStart) || !t.isBefore(bucketEnd)) {
                throw new CoreException(ROLLUP_TIME_RANGE_ERROR);
            }
        }

        return true;
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
                .mapToLong(OhlcCandle::getVolume)
                .sum();

        return new OhlcRollup(open, high, low, close, volume);
    }

    private static <T extends OhlcCandle> List<T> sortedCopy(List<T> candles) {
        return candles.stream()
                .sorted(Comparator.comparing(OhlcCandle::getBucketTime))
                .toList();
    }
}
