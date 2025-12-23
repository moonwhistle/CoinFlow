package com.coinflow.aggregation.service.rollup;

import com.coinflow.aggregation.event.Ohlc1mFlushedEvent;

public interface FlushedService {

    void onOhlc1mFlushed(Ohlc1mFlushedEvent event);
}
