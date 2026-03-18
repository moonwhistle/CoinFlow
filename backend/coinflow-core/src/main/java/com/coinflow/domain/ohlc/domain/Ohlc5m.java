package com.coinflow.domain.ohlc.domain;

import com.coinflow.domain.symbol.domain.Symbol;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "ohlc_5m", uniqueConstraints = {
        @UniqueConstraint(
                name = "uk_ohlc_5m_symbol_bucket",
                columnNames = {"symbol_id", "bucket_time"}
        )
})
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Ohlc5m extends AbstractOhlc {

    @Builder
    private Ohlc5m(Symbol symbol, LocalDateTime bucketTime) {
        this.symbol = symbol;
        this.bucketTime = bucketTime;
    }
}

