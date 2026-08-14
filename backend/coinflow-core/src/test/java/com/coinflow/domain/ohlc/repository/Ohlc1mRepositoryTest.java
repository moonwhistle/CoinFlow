package com.coinflow.domain.ohlc.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.coinflow.domain.ohlc.domain.Ohlc1m;
import com.coinflow.domain.ohlc.snapshot.OhlcRangeAggregate;
import com.coinflow.domain.symbol.domain.Symbol;
import com.coinflow.domain.symbol.domain.vo.MarketType;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.ContextConfiguration;

@DataJpaTest
@ContextConfiguration(classes = Ohlc1mRepositoryTest.JpaTestConfiguration.class)
class Ohlc1mRepositoryTest {

    @Autowired
    private TestEntityManager entityManager;

    @Autowired
    private Ohlc1mRepository repository;

    private Symbol symbol;

    @BeforeEach
    void setUp() {
        symbol = entityManager.persistAndFlush(Symbol.builder()
                .symbol("btcusdt")
                .exchange("BINANCE")
                .name("Bitcoin / USDT")
                .active(true)
                .marketType(MarketType.SPOT)
                .providerSymbol("btcusdt")
                .build());
    }

    @Test
    void aggregatesOnlyCandlesInsideRequestedRange() {
        LocalDateTime base = LocalDateTime.of(2026, 8, 14, 0, 0);
        persistCandle(base, "80", "90", "70", "85", 100_000_000L);
        persistCandle(base.plusMinutes(1), "100", "110", "90", "105", 200_000_000L);
        persistCandle(base.plusMinutes(2), "105", "120", "95", "115", 300_000_000L);
        persistCandle(base.plusMinutes(3), "115", "118", "100", "101", 400_000_000L);
        persistCandle(base.plusMinutes(4), "101", "200", "1", "150", 500_000_000L);
        entityManager.flush();
        entityManager.clear();

        LocalDateTime start = base.plusMinutes(1);
        LocalDateTime end = base.plusMinutes(4);

        Ohlc1m first = repository
                .findFirstBySymbolIdAndBucketTimeGreaterThanEqualAndBucketTimeLessThanOrderByBucketTimeAsc(
                        symbol.getId(), start, end)
                .orElseThrow();
        Ohlc1m last = repository
                .findFirstBySymbolIdAndBucketTimeGreaterThanEqualAndBucketTimeLessThanOrderByBucketTimeDesc(
                        symbol.getId(), start, end)
                .orElseThrow();
        OhlcRangeAggregate aggregate = repository.aggregateInBucketRange(symbol.getId(), start, end);

        assertThat(first.getBucketTime()).isEqualTo(base.plusMinutes(1));
        assertThat(first.getOpenPrice()).isEqualByComparingTo("100");
        assertThat(last.getBucketTime()).isEqualTo(base.plusMinutes(3));
        assertThat(last.getClosePrice()).isEqualByComparingTo("101");
        assertThat(aggregate.highPrice()).isEqualByComparingTo("120");
        assertThat(aggregate.lowPrice()).isEqualByComparingTo("90");
        assertThat(aggregate.volume()).isEqualTo(900_000_000L);
    }

    private void persistCandle(
            LocalDateTime bucketTime,
            String open,
            String high,
            String low,
            String close,
            long volume) {
        entityManager.persist(Ohlc1m.builder()
                .symbol(symbol)
                .bucketTime(bucketTime)
                .open(new BigDecimal(open))
                .high(new BigDecimal(high))
                .low(new BigDecimal(low))
                .close(new BigDecimal(close))
                .volume(volume)
                .build());
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @EntityScan(basePackageClasses = {Ohlc1m.class, Symbol.class})
    @EnableJpaRepositories(basePackageClasses = Ohlc1mRepository.class)
    static class JpaTestConfiguration {
    }
}
