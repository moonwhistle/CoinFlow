package com.coinflow.domain.ohlc.service;

import com.coinflow.domain.tick.event.TickRawEvent;

public interface OhlcService {
    void process(TickRawEvent event);
}
