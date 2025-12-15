package com.coinflow.exception;

import lombok.Getter;

@Getter
public abstract class BaseException extends RuntimeException{

    private final BaseErrorCode errorCode;

    protected BaseException(BaseErrorCode errorCode) {
        super(errorCode.customCode() + ": " + errorCode.message());
        this.errorCode = errorCode;
    }
}
