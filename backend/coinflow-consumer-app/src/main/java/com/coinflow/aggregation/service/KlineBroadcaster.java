package com.coinflow.aggregation.service;

import com.coinflow.event.kline.KlineEvent;

/**
 * SRP: Responsibility is ONLY to propagate kline events.
 * Caller is responsible for serializing KlineEvent to JSON (pre-serialized) before calling this method.
 */
public interface KlineBroadcaster {
    void broadcast(KlineEvent event, String preSerializedJson);
}
