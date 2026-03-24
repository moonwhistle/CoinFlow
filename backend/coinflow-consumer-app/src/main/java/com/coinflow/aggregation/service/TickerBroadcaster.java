package com.coinflow.aggregation.service;
 
import com.coinflow.event.ticker.TickerEvent;
 
/**
 * SRP: Responsibility is ONLY to propagate ticker events.
 */
public interface TickerBroadcaster {
    void broadcast(TickerEvent event);
}
