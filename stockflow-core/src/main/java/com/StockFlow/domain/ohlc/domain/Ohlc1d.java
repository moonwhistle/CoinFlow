package com.StockFlow.domain.ohlc.domain;

import com.StockFlow.domain.symbol.domain.Symbol;
import jakarta.persistence.Entity;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.NoArgsConstructor;

@Entity
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Ohlc1d extends AbstractOhlc {

    @Builder
    private Ohlc1d(Symbol symbol, LocalDateTime bucketTime) {
        this.symbol = symbol;
        this.bucketTime = bucketTime;
    }
}

