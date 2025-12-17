package com.coinflow.common.exception;

import com.coinflow.exception.BaseErrorCode;
import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
public enum CoreErrorCode implements BaseErrorCode {

    // policy
    TICK_INVALID_QUANTITY("POLICY-001", "Tick quantity must be non-null and non-negative", 500),

    // domain - symbol
    NOT_FOUND_SYMBOL("SYM-001", "Not Found Symbol", 404),

    // domain - ohlc
    DUPLICATE_OHLC_1M("O1M-001", "Duplicated row", 400)
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
