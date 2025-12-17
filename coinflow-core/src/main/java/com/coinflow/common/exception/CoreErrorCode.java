package com.coinflow.common.exception;

import com.coinflow.exception.BaseErrorCode;
import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
public enum CoreErrorCode implements BaseErrorCode {

    TICK_INVALID_QUANTITY("CORE-001", "Tick quantity must be non-null and non-negative", 500),
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
