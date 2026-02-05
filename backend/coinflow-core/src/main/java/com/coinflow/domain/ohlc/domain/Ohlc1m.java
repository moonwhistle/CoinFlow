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
}
