package com.coinflow.exception;

import lombok.Getter;

@Getter
public class PublishException extends RuntimeException{

    private final PublishErrorCode errorCode;

    public PublishException(
            PublishErrorCode errorCode,
            String message,
            Throwable cause
    ) {
        super(message, cause);
        this.errorCode = errorCode;
    }
}
