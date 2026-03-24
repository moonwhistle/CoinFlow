package com.coinflow.aggregation.service;
 
import com.coinflow.domain.aggregation.domain.vo.KlineSnapshot;
import com.coinflow.event.kline.KlineEvent;
 
/**
 * SRP: Mapper between Domain VO and Event DTO.
 * DRY: Centralized mapping logic.
 */
public class KlineEventMapper {
 
    public static KlineEvent toEvent(String symbol, String interval, KlineSnapshot snapshot) {
        return KlineEvent.builder()
                .symbol(symbol)
                .interval(interval)
                .startTime(snapshot.startTime())
                .closeTime(snapshot.closeTime())
                .open(snapshot.open())
                .high(snapshot.high())
                .low(snapshot.low())
                .close(snapshot.close())
                .volume(snapshot.volume())
                .trades(snapshot.trades())
                .closed(snapshot.closed())
                .build();
    }
}
