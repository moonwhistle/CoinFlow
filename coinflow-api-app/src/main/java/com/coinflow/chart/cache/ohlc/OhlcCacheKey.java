package com.coinflow.chart.cache.ohlc;

import com.coinflow.domain.ohlc.constant.OhlcInterval;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

public class OhlcCacheKey {

    private static final String PREFIX = "ohlc:chart";
    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("yyyyMMddHHmm");

    private OhlcCacheKey() {
    }

    public static String chartKey(
            Long symbolId,
            OhlcInterval interval,
            int candles,
            LocalDateTime endExclusive
    ) {
        return String.join(":",
                PREFIX,
                interval.name(),
                String.valueOf(symbolId),
                String.valueOf(candles),
                endExclusive.format(TIME_FORMAT)
        );
    }
}
