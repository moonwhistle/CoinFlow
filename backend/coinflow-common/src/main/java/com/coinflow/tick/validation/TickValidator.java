package com.coinflow.tick.validation;

import java.math.BigDecimal;
import java.util.Objects;

/**
 * 틱 데이터의 유효성을 검증하는 공통 Validator 클래스입니다.
 * 인코딩 전 원천 데이터의 무결성을 보장하여 바이너리 스트림 오염을 방지합니다.
 */
public final class TickValidator {

    private TickValidator() {}

    /**
     * 모든 필수 필드의 유효성을 검증합니다.
     * 
     * @throws IllegalArgumentException 유효성 검증 실패 시
     */
    public static void validate(String symbol, BigDecimal price, BigDecimal quantity, long eventTime) {
        validateSymbol(symbol);
        validatePrice(price);
        validateQuantity(quantity);
        validateEventTime(eventTime);
    }

    private static void validateSymbol(String symbol) {
        if (symbol == null || symbol.isBlank()) {
            throw new IllegalArgumentException("Symbol cannot be null or empty");
        }
    }

    private static void validatePrice(BigDecimal price) {
        Objects.requireNonNull(price, "Price cannot be null");
        if (price.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("Price must be greater than zero: " + price);
        }
    }

    private static void validateQuantity(BigDecimal quantity) {
        Objects.requireNonNull(quantity, "Quantity cannot be null");
        if (quantity.compareTo(BigDecimal.ZERO) < 0) {
            throw new IllegalArgumentException("Quantity cannot be negative: " + quantity);
        }
    }

    private static void validateEventTime(long eventTime) {
        if (eventTime <= 0) {
            throw new IllegalArgumentException("Invalid EventTime: " + eventTime);
        }
    }
}
