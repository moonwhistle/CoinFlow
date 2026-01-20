package com.coinflow.common.exception;

import com.coinflow.exception.BaseErrorCode;
import com.coinflow.exception.BaseException;

public class ApiException extends BaseException {

    public ApiException(BaseErrorCode errorCode) {
        super(errorCode);
    }
}
