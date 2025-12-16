package com.coinflow.processor;

import com.coinflow.domain.tick.event.TickRawEvent;
import org.springframework.stereotype.Component;

@Component
public class DefaultOhlcProcessor implements OhlcProcessor{

    @Override
    public void process(TickRawEvent event) {
        // TODO: saveTick
    }
}
