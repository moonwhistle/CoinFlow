package com.coinflow.chart.controller;

import com.coinflow.chart.controller.response.OhlcChartResponse;
import com.coinflow.chart.service.Ohlc1mChartService;
import com.coinflow.common.path.chart.ChartPath;
import com.coinflow.domain.ohlc.constant.OhlcInterval;
import com.coinflow.domain.ohlc.snapshot.OhlcCandleSnapshot;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
public class ChartController {

    private static final String DEFAULT_INTERVAL = "1m";
    public static final String DEFAULT_CANDLES_STR = "120";

    private final Ohlc1mChartService ohlc1mChartService;

    @GetMapping(ChartPath.OHLC)
    public ResponseEntity<OhlcChartResponse> showOhlcChart(
            @PathVariable Long symbolId,
            @RequestParam(defaultValue = DEFAULT_INTERVAL) String interval,
            @RequestParam(defaultValue = DEFAULT_CANDLES_STR) int candles
    ) {
        List<OhlcCandleSnapshot> snapshots = ohlc1mChartService.show(symbolId);

        return ResponseEntity.ok(new OhlcChartResponse(
                symbolId,
                OhlcInterval.M1,
                snapshots
        ));
    }
}
