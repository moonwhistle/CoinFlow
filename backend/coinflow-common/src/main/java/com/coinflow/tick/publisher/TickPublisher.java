package com.coinflow.tick.publisher;

import com.coinflow.tick.event.TickRawEvent;

public interface TickPublisher {

    void publish(TickRawEvent tickRawEvent);
}
