package com.coinflow.domain.log.domain;

import com.coinflow.domain.log.domain.vo.ReconciliationReason;
import com.coinflow.domain.symbol.domain.Symbol;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "missing_tick_log")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class MissingTickLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "symbol_id", nullable = false)
    private Symbol symbol;

    @Column(nullable = false, length = 10)
    private String intervalType;

    @Column(nullable = false)
    private LocalDateTime bucketTime;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ReconciliationReason reason;

    @Column(precision = 18, scale = 6)
    private BigDecimal expectedClosePrice;

    @Column(precision = 18, scale = 6)
    private BigDecimal actualClosePrice;

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Builder
    private MissingTickLog(Symbol symbol, String intervalType, LocalDateTime bucketTime,
            ReconciliationReason reason, BigDecimal expectedClosePrice, BigDecimal actualClosePrice) {
        this.symbol = symbol;
        this.intervalType = intervalType;
        this.bucketTime = bucketTime;
        this.reason = reason;
        this.expectedClosePrice = expectedClosePrice;
        this.actualClosePrice = actualClosePrice;
    }

    @PrePersist
    protected void onPrePersist() {
        if (this.createdAt == null) {
            this.createdAt = LocalDateTime.now();
        }
    }
}
