package com.coinflow.aggregation.service;

/**
 * SRP: Responsibility is ONLY to propagate ticker events.
 * Caller is responsible for serializing TickerEvent to JSON (pre-serialized) before calling this method.
 */
public interface TickerBroadcaster {
    void broadcast(String preSerializedJson);
}
