package com.coinflow.processor;

import com.coinflow.domain.tick.event.TickRawEvent;

public interface OhlcProcessor {
    void process(TickRawEvent event);
}
