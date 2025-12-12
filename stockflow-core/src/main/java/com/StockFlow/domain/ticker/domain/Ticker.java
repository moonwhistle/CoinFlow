package com.StockFlow.domain.ticker.domain;

import com.StockFlow.domain.symbol.domain.Symbol;
import com.StockFlow.domain.ticker.exception.TickerErrorCode;
import com.StockFlow.domain.ticker.exception.TickerException;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@Entity
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder
public class Ticker {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "symbol_id", nullable = false)
    private Symbol symbol;

    @Column(nullable = false, precision = 18, scale = 6)
    private BigDecimal lastPrice;

    @Column(precision = 18, scale = 6)
    private BigDecimal change; // 가격 변화량

    @Column(precision = 18, scale = 6)
    private BigDecimal changePercent; // 변동률 %

    @Column
    private Long volume;

    // 실제 시장 시간
    @Column(nullable = false)
    private LocalDateTime timestamp;

    // DB 저장 시간 (서버 기준)
    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column
    private LocalDateTime updatedAt;

    @PrePersist
    public void prePersist() {
        if(timestamp == null) {
            throw new TickerException(TickerErrorCode.TIMESTAMP_REQUIRED);
        }

        this.createdAt = LocalDateTime.now();
        this.updatedAt = this.createdAt;
    }

    @PreUpdate
    public void preUpdate() {
        this.updatedAt = LocalDateTime.now();
    }
}
