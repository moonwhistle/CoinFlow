package com.coinflow.common.exception;

import com.coinflow.exception.BaseErrorCode;
import com.coinflow.exception.BaseException;

public class CoreException extends BaseException {

    public CoreException(BaseErrorCode errorCode) {
        super(errorCode);
    }
}
