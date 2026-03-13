package com.coinflow.replay.batch.reader;

import com.coinflow.domain.symbol.domain.Symbol;
import com.coinflow.domain.symbol.service.SymbolService;
import com.coinflow.replay.batch.common.ReconciliationBatchConstants;
import com.coinflow.replay.batch.model.RollupTarget;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.item.ItemReader;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

public class OhlcRollupReader implements ItemReader<RollupTarget> {
    private static final Logger log = LoggerFactory.getLogger(OhlcRollupReader.class);

    private final String symbolName;
    private final int intervalMinutes;
    private final long startTimeMs;
    private final long endTimeMs;
    private final SymbolService symbolService;

    private Iterator<RollupTarget> targetIterator;
    private boolean initialized = false;

    public OhlcRollupReader(String symbolName, int intervalMinutes, long startTimeMs, long endTimeMs,
            SymbolService symbolService) {
        this.symbolName = symbolName;
        this.intervalMinutes = intervalMinutes;
        this.startTimeMs = startTimeMs;
        this.endTimeMs = endTimeMs;
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

        LocalDateTime startBoundary = ReconciliationBatchConstants.toLocalDateTime(startTimeMs);
        LocalDateTime endBoundary = ReconciliationBatchConstants.toLocalDateTime(endTimeMs);

        // Calculate the first bucket that contains startBoundary
        int startMinute = startBoundary.getMinute();
        int truncatedStartMinute = (startMinute / intervalMinutes) * intervalMinutes;
        LocalDateTime current = startBoundary.withMinute(truncatedStartMinute).withSecond(0).withNano(0);

        // Calculate the last bucket that overlaps with the range [startBoundary,
        // endBoundary)
        // endBoundary is exclusive, so the last relevant minute is endBoundary - 1m
        LocalDateTime lastRelevantMinute = endBoundary.minusMinutes(1);
        int lastMinute = lastRelevantMinute.getMinute();
        int truncatedLastMinute = (lastMinute / intervalMinutes) * intervalMinutes;
        LocalDateTime lastBucket = lastRelevantMinute.withMinute(truncatedLastMinute).withSecond(0).withNano(0);

        List<RollupTarget> list = new ArrayList<>();
        // Iterate while current bucket is not after the last bucket
        while (!current.isAfter(lastBucket)) {
            list.add(new RollupTarget(symbol, current, intervalMinutes));
            current = current.plusMinutes(intervalMinutes);
        }

        log.info("Symbol {} has {} rollup targets for {}m interval within range {} to {} (Buckets: {} to {})",
                symbolName, list.size(), intervalMinutes, startBoundary, endBoundary,
                startBoundary.withMinute(truncatedStartMinute).withSecond(0).withNano(0), lastBucket);

        targetIterator = list.iterator();
        initialized = true;
    }
}
