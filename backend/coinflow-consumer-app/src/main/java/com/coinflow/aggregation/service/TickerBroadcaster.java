package com.coinflow.aggregation.service;
 
import com.coinflow.event.ticker.TickerEvent;
 
/**
 * Interface for ticker broadcasting.
 */
public interface TickerBroadcaster {
    void broadcast(TickerEvent event);
}
