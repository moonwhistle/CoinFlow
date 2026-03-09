package com.coinflow.replay.common.exception;

import com.coinflow.exception.BaseErrorCode;
import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
public enum ReplayErrorCode implements BaseErrorCode {

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
