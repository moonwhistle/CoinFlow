package com.coinflow.chart.controller.response;

import com.coinflow.domain.ohlc.constant.OhlcInterval;
import com.coinflow.domain.ohlc.snapshot.OhlcCandleSnapshot;
import java.util.List;

public record OhlcChartResponse(
        Long symbolId,
        OhlcInterval interval,
        List<OhlcCandleSnapshot> candles
) {
}
