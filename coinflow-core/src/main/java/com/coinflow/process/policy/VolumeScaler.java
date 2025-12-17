package com.coinflow.process.policy;

import com.coinflow.common.exception.CoreErrorCode;
import com.coinflow.common.exception.CoreException;
import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Tick quantity(BigDecimal)를 정수(long)로 스케일링하여 누적 가능한 형태로 변환한다.
 * volume은 고정 소수점 방식으로 저장 (8자리면 0.00000001 단위)
 * 누적(volume 합산)은 long으로 수행
 */
public final class VolumeScaler {

    private static final int SCALE = 8; // 8자리 고정
    private static final RoundingMode ROUNDING = RoundingMode.DOWN;
    private static final BigDecimal SCALE_FACTOR = BigDecimal.TEN
            .pow(SCALE);

    private VolumeScaler() {
    }

    public static long toLong(BigDecimal quantity) {
        validate(quantity);

        BigDecimal scaled = quantity.multiply(SCALE_FACTOR)
                .setScale(0, ROUNDING);

        return scaled.longValueExact();
    }

    public static BigDecimal toBigDecimal(long volume) {
        return BigDecimal.valueOf(volume).divide(SCALE_FACTOR, SCALE, ROUNDING);
    }

    private static void validate(BigDecimal quantity) {
        if (quantity == null) {
            throw new CoreException(CoreErrorCode.TICK_INVALID_QUANTITY);
        }
        if (quantity.signum() < 0) {
            throw new CoreException(CoreErrorCode.TICK_INVALID_QUANTITY);
        }
    }
}
