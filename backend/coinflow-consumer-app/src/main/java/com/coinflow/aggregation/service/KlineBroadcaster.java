package com.coinflow.aggregation.service;
 
import com.coinflow.event.kline.KlineEvent;
 
/**
 * SRP: Responsibility is ONLY to propagate kline events.
 */
public interface KlineBroadcaster {
    void broadcast(KlineEvent event);
}
