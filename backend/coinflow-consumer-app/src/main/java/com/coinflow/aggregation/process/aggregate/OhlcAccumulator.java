package com.coinflow.aggregation.process.aggregate;

import com.coinflow.domain.ohlc.policy.VolumeScaler;
import java.math.BigDecimal;
import java.time.Instant;
import lombok.Getter;

/**
 * 시간 버킷(1m/5m/30m/...) 단위의 OHLC 누적기.
 *
 * <p>
 * open은 최초 가격, close는 가장 늦은 eventTime을 가진 가격으로 결정한다.
 * </p>
 * <p>
 * volume은 고정 소수점 스케일링된 long(VolumeScaler)을 누적한다.
 * </p>
 * <p>
 * Note: 이 클래스는 Thread-Safe 합니다.
 * </p>
 */
@Getter
public class OhlcAccumulator {

    private final BigDecimal open;
    private BigDecimal high;
    private BigDecimal low;
    private BigDecimal close;
    private long volume;

    private Instant closeTime;

    private OhlcAccumulator(BigDecimal open, long volume, Instant eventTime) {
        this.open = open;
        this.high = open;
        this.low = open;
        this.close = open;
        this.volume = volume;
        this.closeTime = eventTime;
    }

    public static OhlcAccumulator first(BigDecimal price, long volume, Instant eventTime) {
        return new OhlcAccumulator(price, volume, eventTime);
    }

    public synchronized void apply(BigDecimal price, long vol, Instant eventTime) {
        if (price.compareTo(high) > 0) {
            high = price;
        }
        if (price.compareTo(low) < 0) {
            low = price;
        }

        // late tick 보정 (더 늦은 eventTime의 가격을 close로)
        if (eventTime.isAfter(closeTime)) {
            closeTime = eventTime;
            close = price;
        }

        // volume overflow 검증
        volume = Math.addExact(volume, vol);
    }
}
