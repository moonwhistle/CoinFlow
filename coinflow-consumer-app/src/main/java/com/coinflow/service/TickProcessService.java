package com.coinflow.service;

import com.coinflow.domain.tick.event.TickRawEvent;
import com.coinflow.process.facade.OhlcFacade;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@Slf4j
public class TickProcessService {

    private final OhlcFacade ohlcFacade;

    public void process(TickRawEvent event) {
        log.info(
                "[Consumer] Processing tick event - symbol={}, price={}, eventTime={}",
                event.symbol(),
                event.price(),
                event.eventTime()
        );

        try {
            ohlcFacade.process(event);
            log.debug("[Consumer] Successfully processed tick event - symbol={}", event.symbol());
        } catch (Exception e) {
            log.error(
                    "[Consumer] Failed to process tick event - symbol={}, price={}",
                    event.symbol(),
                    event.price(),
                    e
            );

            throw e;
        }
    }
}
