package com.coinflow.domain.ohlc.domain;

import com.coinflow.domain.symbol.domain.Symbol;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "ohlc_1m")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Ohlc1m extends AbstractOhlc {

    @Builder
    private Ohlc1m(Symbol symbol, LocalDateTime bucketTime, BigDecimal open, BigDecimal high, BigDecimal low,
            BigDecimal close, Long volume) {
        this.symbol = symbol;
        this.bucketTime = bucketTime;
        this.openPrice = open;
        this.highPrice = high;
        this.lowPrice = low;
        this.closePrice = close;
        this.volume = volume;
    }

    public void merge(BigDecimal open, BigDecimal high, BigDecimal low, BigDecimal close, Long volume) {
        // High: Keep the higher value
        if (high.compareTo(this.highPrice) > 0) {
            this.highPrice = high;
        }
        // Low: Keep the lower value
        if (low.compareTo(this.lowPrice) < 0) {
            this.lowPrice = low;
        }
        // Volume: Accumulate
        this.volume += volume;

        // Open/Close logic for late arrival:
        // Since we don't store lastTickTime, we cannot definitively determine order.
        // Strategy: Stick to DB values for Open/Close unless DB was empty (which
        // shouldn't happen here as it's an update).
        // However, if we assume the flush might be a "correction" of the Close price?
        // No, partial accumulator only knows its own limited ticks. It doesn't know the
        // global last tick.
        // So keeping DB Close is safer than overwriting with a random late tick's
        // price.
        // Thus, we intentionally DO NOT update Open/Close prices during a merge
        // operation.
    }
}
