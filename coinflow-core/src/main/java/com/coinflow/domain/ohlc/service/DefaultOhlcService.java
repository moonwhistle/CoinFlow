package com.coinflow.domain.ohlc.service;

import com.coinflow.domain.tick.event.TickRawEvent;
import org.springframework.stereotype.Component;

@Component
public class DefaultOhlcService implements OhlcService {

    @Override
    public void process(TickRawEvent event) {
        // TODO: saveTick
    }
}
