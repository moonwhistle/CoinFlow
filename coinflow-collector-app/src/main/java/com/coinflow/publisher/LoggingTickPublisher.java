package com.coinflow.publisher;

import com.coinflow.event.TickRawEvent;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class LoggingTickPublisher implements TickPublisher{

    @Override
    public void publish(TickRawEvent tickRawEvent) {
        log.info("{}", tickRawEvent);
    }
}
