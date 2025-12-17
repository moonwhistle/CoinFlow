package com.coinflow.domain.ohlc.domain;

import com.coinflow.domain.symbol.domain.Symbol;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "ohlc_30m")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Ohlc30m extends AbstractOhlc {

    @Builder
    private Ohlc30m(Symbol symbol, LocalDateTime bucketTime) {
        this.symbol = symbol;
        this.bucketTime = bucketTime;
    }
}

