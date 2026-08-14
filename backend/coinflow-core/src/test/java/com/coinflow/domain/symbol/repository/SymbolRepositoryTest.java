package com.coinflow.domain.symbol.repository;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.coinflow.domain.ohlc.domain.Ohlc1m;
import com.coinflow.domain.symbol.domain.Symbol;
import com.coinflow.domain.symbol.domain.vo.MarketType;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.ContextConfiguration;

@DataJpaTest
@ContextConfiguration(classes = SymbolRepositoryTest.JpaTestConfiguration.class)
class SymbolRepositoryTest {

    @Autowired
    private SymbolRepository symbolRepository;

    @Test
    void rejectsDuplicateSymbolCodes() {
        symbolRepository.saveAndFlush(symbol("Bitcoin / USDT"));

        assertThatThrownBy(() -> symbolRepository.saveAndFlush(symbol("Duplicated Bitcoin")))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    private Symbol symbol(String name) {
        return Symbol.builder()
                .symbol("btcusdt")
                .exchange("BINANCE")
                .name(name)
                .active(true)
                .marketType(MarketType.SPOT)
                .providerSymbol("btcusdt")
                .build();
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @EntityScan(basePackageClasses = {Ohlc1m.class, Symbol.class})
    @EnableJpaRepositories(basePackageClasses = SymbolRepository.class)
    static class JpaTestConfiguration {
    }
}
