package com.coinflow.domain.ohlc.domain;

import com.coinflow.domain.symbol.domain.Symbol;
import jakarta.persistence.Column;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.MappedSuperclass;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@MappedSuperclass
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PROTECTED)
public abstract class AbstractOhlc {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    protected Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "symbol_id", nullable = false)
    protected Symbol symbol;

    /**
     * 캔들 버킷 시작 시각 (1M, 5M, 30M, 1D 모두 동일 개념)
     */
    @Column(nullable = false)
    protected LocalDateTime bucketTime;

    @Column(nullable = false, precision = 18, scale = 6)
    protected BigDecimal openPrice;

    @Column(nullable = false, precision = 18, scale = 6)
    protected BigDecimal highPrice;

    @Column(nullable = false, precision = 18, scale = 6)
    protected BigDecimal lowPrice;

    @Column(nullable = false, precision = 18, scale = 6)
    protected BigDecimal closePrice;

    @Column(nullable = false)
    protected Long volume;

    @Column(nullable = false)
    protected LocalDateTime updatedAt;

    @PrePersist
    protected void prePersist() {
        this.updatedAt = LocalDateTime.now();
    }

    @PreUpdate
    protected void preUpdate() {
        this.updatedAt = LocalDateTime.now();
    }
}
