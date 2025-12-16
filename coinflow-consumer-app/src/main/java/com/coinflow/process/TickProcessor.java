package com.coinflow.process;

import com.coinflow.domain.tick.event.TickRawEvent;
import com.coinflow.processor.OhlcProcessor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class TickProcessor {

    private final OhlcProcessor ohlcProcessor;

    public void process(TickRawEvent event) {
        ohlcProcessor.process(event);

        log.debug(
                "tick. symbol={}, price={}, time={}",
                event.symbol(),
                event.price(),
                event.eventTime()
        );
    }
}
