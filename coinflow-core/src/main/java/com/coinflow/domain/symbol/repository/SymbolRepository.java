package com.coinflow.domain.symbol.repository;

import com.coinflow.domain.symbol.domain.Symbol;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SymbolRepository extends JpaRepository<Symbol, Long> {
}
