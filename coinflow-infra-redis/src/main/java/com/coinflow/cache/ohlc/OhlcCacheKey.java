package com.coinflow.cache.ohlc;

import com.coinflow.domain.ohlc.constant.OhlcInterval;
import java.time.LocalDateTime;

public class OhlcCacheKey {

    private OhlcCacheKey() {
    }

    public static String recent(Long symbolId, OhlcInterval interval, LocalDateTime endExclusive) {
        return "ohlc:chart:%s:%d:%s".formatted(interval.name(), symbolId, endExclusive);
    }
}
