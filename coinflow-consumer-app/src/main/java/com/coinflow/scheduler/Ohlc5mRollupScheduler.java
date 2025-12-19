package com.coinflow.scheduler;

import com.coinflow.process.service.Ohlc5mRollupService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class Ohlc5mRollupScheduler {

    private final Ohlc5mRollupService rollupService;

    @Scheduled(fixedDelay = 1000)
    public void rollupClosedBuckets() {
        rollupService.rollupClosedBuckets();
    }
}
