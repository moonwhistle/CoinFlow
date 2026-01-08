package com.coinflow.aggregation.process.rollup;

import com.coinflow.domain.ohlc.domain.OhlcCandle;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

public final class OhlcRollupCalculator {

    private OhlcRollupCalculator() {
    }

    public static <T extends OhlcCandle> Optional<OhlcRollup> rollup(List<T> candles) {
        if (candles == null || candles.isEmpty()) {
            return Optional.empty();
        }

        validateSorted(candles);

        return Optional.of(calculate(candles));
    }

    private static <T extends OhlcCandle> void validateSorted(List<T> candles) {
        for (int i = 1; i < candles.size(); i++) {

            if (candles.get(i - 1).getBucketTime()
                    .isAfter(candles.get(i).getBucketTime())) {
                throw new IllegalArgumentException("candles must be sorted by bucketTime");
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
