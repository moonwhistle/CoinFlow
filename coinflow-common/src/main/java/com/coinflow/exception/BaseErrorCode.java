package com.coinflow.exception;

public interface BaseErrorCode {

    int httpStatus();

    String customCode();

    String message();
}
