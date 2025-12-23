package com.coinflow.domain.symbol.service;

import com.coinflow.common.exception.CoreErrorCode;
import com.coinflow.common.exception.CoreException;
import com.coinflow.domain.symbol.domain.Symbol;
import com.coinflow.domain.symbol.repository.SymbolRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class SymbolService {

    private final SymbolRepository symbolRepository;

    @Cacheable(
            cacheNames = "symbols",
            key = "'id:' + #symbolId"
    )
    @Transactional(readOnly = true)
    public Symbol findSymbol(Long symbolId) {
        return symbolRepository.findById(symbolId)
                .orElseThrow(() -> new CoreException(CoreErrorCode.NOT_FOUND_SYMBOL));
    }

    @Cacheable(
            cacheNames = "symbols",
            key = "'code:' + #symbol"
    )
    @Transactional(readOnly = true)
    public Symbol findBySymbol(String symbol) {
        return symbolRepository.findBySymbol(symbol)
                .orElseThrow(() -> new CoreException(CoreErrorCode.NOT_FOUND_SYMBOL));
    }
}
