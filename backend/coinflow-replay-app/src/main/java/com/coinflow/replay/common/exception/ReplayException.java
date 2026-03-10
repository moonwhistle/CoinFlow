package com.coinflow.replay.common.exception;

import com.coinflow.exception.BaseErrorCode;
import com.coinflow.exception.BaseException;

public class ReplayException extends BaseException {

    public ReplayException(BaseErrorCode errorCode) {
        super(errorCode);
    }
}
