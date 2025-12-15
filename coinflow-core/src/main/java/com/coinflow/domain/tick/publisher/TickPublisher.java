package com.coinflow.domain.tick.publisher;

import com.coinflow.domain.tick.event.TickRawEvent;

public interface TickPublisher {

    void publish(TickRawEvent tickRawEvent);
}
