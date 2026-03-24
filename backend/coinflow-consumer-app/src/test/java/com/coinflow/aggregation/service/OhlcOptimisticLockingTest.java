package com.coinflow.aggregation.service;

import com.coinflow.domain.ohlc.domain.Ohlc1m;
import com.coinflow.domain.ohlc.repository.Ohlc1mRepository;
import com.coinflow.domain.symbol.domain.Symbol;
import com.coinflow.domain.symbol.domain.vo.MarketType;
import com.coinflow.domain.symbol.repository.SymbolRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@ActiveProfiles("test")
public class OhlcOptimisticLockingTest {

    @Autowired
    private Ohlc1mRepository ohlc1mRepository;

    @Autowired
    private SymbolRepository symbolRepository;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private TransactionTemplate transactionTemplate;
    private Symbol savedSymbol;
    private LocalDateTime bucketTime;

    @BeforeEach
    void setUp() {
        transactionTemplate = new TransactionTemplate(transactionManager);

        // 데이터 초기화
        ohlc1mRepository.deleteAllInBatch();
        symbolRepository.deleteAllInBatch();

        savedSymbol = symbolRepository.save(Symbol.builder()
                .symbol("btcusdt")
                .exchange("BINANCE")
                .name("btcusdt")
                .active(true)
                .marketType(MarketType.SPOT)
                .build());

        bucketTime = LocalDateTime.of(2024, 1, 1, 0, 0);

        // 최초 데이터 저장 (Version: 0)
        ohlc1mRepository.save(Ohlc1m.builder()
                .symbol(savedSymbol)
                .bucketTime(bucketTime)
                .open(BigDecimal.TEN)
                .high(BigDecimal.TEN)
                .low(BigDecimal.TEN)
                .close(BigDecimal.TEN)
                .volume(100L)
                .build());
    }

    @Test
    @DisplayName("동일한 캔들에 대해 동시에 수정을 시도할 경우 낙관적 락(Optimistic Lock)이 작동해야 한다")
    void testOptimisticLockingSuccess() {
        // 1. 첫 번째 트랜잭션에서 엔티티 로드 (Version: 0)
        Ohlc1m candle1 = ohlc1mRepository.findBySymbolIdAndBucketTime(savedSymbol.getId(), bucketTime).orElseThrow();
        assertThat(candle1.getVersion()).isEqualTo(0L);

        // 2. 두 번째 트랜잭션에서 동일한 엔티티 로드 (Version: 0)
        Ohlc1m candle2 = ohlc1mRepository.findBySymbolIdAndBucketTime(savedSymbol.getId(), bucketTime).orElseThrow();
        assertThat(candle2.getVersion()).isEqualTo(0L);

        // 3. 첫 번째 엔티티 수정 및 저장 (Version: 0 -> 1)
        transactionTemplate.execute(status -> {
            candle1.apply(BigDecimal.valueOf(11), BigDecimal.valueOf(11), BigDecimal.valueOf(11),
                    BigDecimal.valueOf(11), 110L);
            return ohlc1mRepository.saveAndFlush(candle1);
        });

        // 4. 두 번째 엔티티(여전히 Version 0인 상태) 수정 및 저장 시도 -> 실패해야 함
        assertThatThrownBy(() -> {
            transactionTemplate.execute(status -> {
                candle2.apply(BigDecimal.valueOf(12), BigDecimal.valueOf(12), BigDecimal.valueOf(12),
                        BigDecimal.valueOf(12), 120L);
                ohlc1mRepository.saveAndFlush(candle2);
                return null;
            });
        }).isInstanceOf(ObjectOptimisticLockingFailureException.class);

        // 5. 최종 결과 확인 (첫 번째 수정만 반영되어 있어야 함)
        Ohlc1m finalCandle = ohlc1mRepository.findBySymbolIdAndBucketTime(savedSymbol.getId(), bucketTime)
                .orElseThrow();
        assertThat(finalCandle.getVersion()).isEqualTo(1L);
        assertThat(finalCandle.getClosePrice()).isEqualByComparingTo(BigDecimal.valueOf(11));
    }
}
