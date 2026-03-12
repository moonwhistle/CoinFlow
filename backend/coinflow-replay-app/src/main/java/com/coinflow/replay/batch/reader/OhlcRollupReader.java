package com.coinflow.replay.batch.reader;

import com.coinflow.domain.symbol.domain.Symbol;
import com.coinflow.domain.symbol.service.SymbolService;
import com.coinflow.replay.batch.common.DirtyBucketTracker;
import com.coinflow.replay.batch.model.RollupTarget;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.item.ItemReader;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors; // Added import

public class OhlcRollupReader implements ItemReader<RollupTarget> {
    private static final Logger log = LoggerFactory.getLogger(OhlcRollupReader.class);

    private final String symbolName;
    private final int intervalMinutes;
    private final long endTimeMs; // Added this field
    private final DirtyBucketTracker tracker;
    private final SymbolService symbolService;

    private Iterator<RollupTarget> targetIterator;
    private boolean initialized = false;

    public OhlcRollupReader(String symbolName, int intervalMinutes, long endTimeMs, DirtyBucketTracker tracker,
            SymbolService symbolService) {
        this.symbolName = symbolName;
        this.intervalMinutes = intervalMinutes;
        this.endTimeMs = endTimeMs;
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
            LocalDateTime endBoundary = LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(endTimeMs),
                    java.time.ZoneId.systemDefault());

            List<RollupTarget> list = targets.stream()
                    .filter(t -> !t.plusMinutes(intervalMinutes).isAfter(endBoundary.plusMinutes(1)))
                    .sorted()
                    .map(t -> new RollupTarget(symbol, t, intervalMinutes))
                    .collect(Collectors.toList());

            if (list.isEmpty()) {
                log.info("All {} dirty targets for symbol {} are still ongoing (endBoundary: {})",
                        targets.size(), symbolName, endBoundary);
            } else {
                log.info("Symbol {} has {} rollup targets for {}m interval", symbolName, list.size(), intervalMinutes);
            }
            targetIterator = list.iterator();
        }
        initialized = true;
    }
}
