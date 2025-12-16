package com.coinflow.processor;

import com.coinflow.domain.tick.event.TickRawEvent;

public class DefaultOhlcProcessor implements OhlcProcessor{

    @Override
    public void process(TickRawEvent event) {
        // TODO: saveTick
    }
}
