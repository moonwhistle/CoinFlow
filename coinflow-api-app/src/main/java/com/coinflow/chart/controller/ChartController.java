package com.coinflow.chart.controller;

import com.coinflow.common.path.chart.ChartPath;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class ChartController {

    @GetMapping(ChartPath.OHLC_1M)
    public ResponseEntity<Void> show1m(@PathVariable String symbolId) {
        // TODO
        return ResponseEntity.ok()
                .build();
    }
}
