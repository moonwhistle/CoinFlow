package com.StockFlow.domain.ticker.exception;

import com.StockFlow.exception.BaseErrorCode;
import lombok.AllArgsConstructor;

@AllArgsConstructor
public enum TickerErrorCode implements BaseErrorCode {

    TIMESTAMP_REQUIRED(400, "TICKER_001", "Ticker timestamp must not be null"),
    ;


    private final int httpStatus;
    private final String customCode;
    private final String message;

    @Override
    public int httpStatus() {
        return httpStatus;
    }

    @Override
    public String customCode() {
        return customCode;
    }

    @Override
    public String message() {
        return message;
    }
}
