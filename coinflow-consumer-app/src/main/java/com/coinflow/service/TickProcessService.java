package com.coinflow.service;

import com.coinflow.domain.tick.event.TickRawEvent;
import com.coinflow.domain.ohlc.service.OhlcService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@Slf4j
public class TickProcessService {

    private final OhlcService ohlcService;

    public void process(TickRawEvent event) {
        ohlcService.process(event);

        log.debug(
                "tick. symbol={}, price={}, time={}",
                event.symbol(),
                event.price(),
                event.eventTime()
        );
    }
}
