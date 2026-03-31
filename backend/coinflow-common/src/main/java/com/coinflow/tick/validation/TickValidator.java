package com.coinflow.tick.validation;

import com.coinflow.common.exception.CommonErrorCode;
import com.coinflow.common.exception.CommonException;
import java.math.BigDecimal;
import java.util.Objects;

/**
 * 틱 데이터의 유효성을 검증하는 공통 Validator 클래스입니다.
 */
public final class TickValidator {

    private TickValidator() {}

    /**
     * 모든 필수 필드의 유효성을 검증합니다.
     * 
     * @throws CommonException 유효성 검증 실패 시
     */
    public static void validate(String symbol, BigDecimal price, BigDecimal quantity, long eventTime) {
        try {
            validateSymbol(symbol);
            validatePrice(price);
            validateQuantity(quantity);
            validateEventTime(eventTime);
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new CommonException(CommonErrorCode.TICK_VALIDATION_FAILED);
        }
    }

    private static void validateSymbol(String symbol) {
        if (symbol == null || symbol.isBlank()) {
            throw new IllegalArgumentException();
        }
    }

    private static void validatePrice(BigDecimal price) {
        Objects.requireNonNull(price);
        if (price.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException();
        }
    }

    private static void validateQuantity(BigDecimal quantity) {
        Objects.requireNonNull(quantity);
        if (quantity.compareTo(BigDecimal.ZERO) < 0) {
            throw new IllegalArgumentException();
        }
    }

    private static void validateEventTime(long eventTime) {
        if (eventTime <= 0) {
            throw new IllegalArgumentException();
        }
    }
}
