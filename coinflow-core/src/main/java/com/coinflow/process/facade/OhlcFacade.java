package com.coinflow.process.facade;

import com.coinflow.domain.symbol.domain.Symbol;
import com.coinflow.domain.symbol.service.SymbolService;
import com.coinflow.domain.tick.event.TickRawEvent;
import com.coinflow.process.service.Ohlc1mUpdateService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class OhlcFacade {

    private final Ohlc1mUpdateService ohlc1mUpdateService;
    private final SymbolService symbolService;

    public void process(TickRawEvent event) {
        Symbol symbol = symbolService.findBySymbol(event.symbol());
        ohlc1mUpdateService.update(symbol, event);
    }
}
