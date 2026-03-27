package com.coinflow.common.exception;

import com.coinflow.exception.BaseErrorCode;
import com.coinflow.exception.BaseException;

public class CommonException extends BaseException {

    public CommonException(BaseErrorCode errorCode) {
        super(errorCode);
    }
}
