package com.coinflow.common.exception;

import com.coinflow.exception.BaseErrorCode;
import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
public enum CommonErrorCode implements BaseErrorCode {

    TICK_SERIALIZATION_FAILED("ERR_COMMON_001", "Tick data serialization failed", 500),
    TICK_VALIDATION_FAILED("ERR_COMMON_002", "Tick data validation failed", 400);


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
