package com.coinflow.aggregation.service.rollup.upserter;

import com.coinflow.common.exception.CoreErrorCode;
import com.coinflow.common.exception.CoreException;
import com.coinflow.domain.ohlc.constant.OhlcInterval;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class OhlcRollupUpserterRegistry {

    private final Map<OhlcInterval, OhlcRollupUpserter> map;

    public OhlcRollupUpserterRegistry(List<OhlcRollupUpserter> upserters) {
        this.map = new EnumMap<>(OhlcInterval.class);

        for (OhlcRollupUpserter upserter : upserters) {
            OhlcInterval key = upserter.supports();
            OhlcRollupUpserter prev = map.putIfAbsent(key, upserter);

            if (prev != null) {
                throw new CoreException(CoreErrorCode.ROLLUP_UPSERTER_DUPLICATED);
            }
        }
    }

    public OhlcRollupUpserter get(OhlcInterval interval) {
        OhlcRollupUpserter upserter = map.get(interval);

        if (upserter == null) {
            throw new CoreException(CoreErrorCode.ROLLUP_UPSERTER_NOT_FOUND);
        }

        return upserter;
    }
}
