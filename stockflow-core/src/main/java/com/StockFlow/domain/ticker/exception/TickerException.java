package com.StockFlow.domain.ticker.exception;

import com.StockFlow.exception.BaseException;

public class TickerException extends BaseException {

    public TickerException(TickerErrorCode errorCode) {
        super(errorCode);
    }
}
