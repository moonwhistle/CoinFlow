package com.coinflow.process.event.service;

import com.coinflow.process.event.Ohlc1mFlushedEvent;

public interface FlushedService {

    void onOhlc1mFlushed(Ohlc1mFlushedEvent event);
}
