package com.StockFlow.exception;

public interface BaseErrorCode {

    int httpStatus();

    String customCode();

    String message();
}
