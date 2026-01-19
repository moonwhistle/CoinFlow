package com.coinflow.common.exception;

import com.coinflow.exception.BaseErrorCode;
import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
public enum ApiErrorCode implements BaseErrorCode {

    UNSUPPORTED_OHLC_INTERVAL("OHLC_001", "Unsupported OHLC interval", 400),
    ;

    private final String code;
    private final String message;
    private final int httpStatus;

    @Override
    public int httpStatus() {
        return httpStatus;
    }

    @Override
    public String customCode() {
        return code;
    }

    @Override
    public String message() {
        return message;
    }
}
