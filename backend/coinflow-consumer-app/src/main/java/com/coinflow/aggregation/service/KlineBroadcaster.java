package com.coinflow.aggregation.service;
 
import com.coinflow.domain.aggregation.domain.vo.KlineSnapshot;
 
/**
 * Interface for kline snapshot broadcasting.
 */
public interface KlineBroadcaster {
    void broadcastAndSave(String symbol, String interval, KlineSnapshot snapshot);
}
