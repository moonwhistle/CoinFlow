package com.coinflow.replay.batch.reader;

import com.coinflow.domain.symbol.domain.Symbol;
import com.coinflow.domain.symbol.service.SymbolService;
import com.coinflow.replay.batch.common.DirtyBucketTracker;
import com.coinflow.replay.batch.model.RollupTarget;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.item.ItemReader;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

public class OhlcRollupReader implements ItemReader<RollupTarget> {
    private static final Logger log = LoggerFactory.getLogger(OhlcRollupReader.class);

    private final String symbolName;
    private final int intervalMinutes;
    private final DirtyBucketTracker tracker;
    private final SymbolService symbolService;

    private Iterator<RollupTarget> targetIterator;
    private boolean initialized = false;

    public OhlcRollupReader(String symbolName, int intervalMinutes, DirtyBucketTracker tracker,
            SymbolService symbolService) {
        this.symbolName = symbolName;
        this.intervalMinutes = intervalMinutes;
        this.tracker = tracker;
        this.symbolService = symbolService;
    }

    @Override
    public RollupTarget read() {
        if (!initialized) {
            initialize();
        }

        if (targetIterator != null && targetIterator.hasNext()) {
            return targetIterator.next();
        }
        return null;
    }

    private void initialize() {
        Symbol symbol = symbolService.findBySymbol(symbolName);
        Set<LocalDateTime> targets = tracker.getTargetBuckets(symbolName, intervalMinutes);

        if (targets.isEmpty()) {
            log.info("No dirty targets found for symbol {} and interval {}m", symbolName, intervalMinutes);
            targetIterator = Collections.emptyIterator();
        } else {
            List<RollupTarget> list = new ArrayList<>();
            targets.stream()
                    .sorted()
                    .forEach(t -> list.add(new RollupTarget(symbol, t, intervalMinutes + "m")));

            log.info("Symbol {} has {} rollup targets for {}m interval", symbolName, list.size(), intervalMinutes);
            targetIterator = list.iterator();
        }
        initialized = true;
    }
}
