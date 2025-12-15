package com.coinflow.publisher;

import com.coinflow.event.TickRawEvent;

public interface TickPublisher {

    void publish(TickRawEvent tickRawEvent);
}
