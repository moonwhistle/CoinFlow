package com.coinflow.replay.batch.processor;

import com.coinflow.domain.ohlc.domain.AbstractOhlc;
import com.coinflow.domain.ohlc.domain.Ohlc1m;
import com.coinflow.domain.ohlc.domain.Ohlc30m;
import com.coinflow.domain.ohlc.domain.Ohlc5m;
import com.coinflow.domain.ohlc.service.Ohlc1mService;
import com.coinflow.domain.ohlc.service.Ohlc30mService;
import com.coinflow.domain.ohlc.service.Ohlc5mService;
import com.coinflow.replay.batch.model.RollupTarget;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.item.ItemProcessor;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

@RequiredArgsConstructor
public class OhlcRollupProcessor implements ItemProcessor<RollupTarget, AbstractOhlc> {
    private static final Logger log = LoggerFactory.getLogger(OhlcRollupProcessor.class);

    private final Ohlc1mService ohlc1mService;
    private final Ohlc5mService ohlc5mService;
    private final Ohlc30mService ohlc30mService;

    @Override
    public AbstractOhlc process(RollupTarget target) {
        LocalDateTime start = target.getBucketTime();
        int intervalMinutes = Integer.parseInt(target.getTargetInterval().replace("m", ""));
        LocalDateTime end = start.plusMinutes(intervalMinutes);

        List<Ohlc1m> sourceCandles = ohlc1mService.findCandlesInBucketRange(target.getSymbol().getId(), start, end);

        if (sourceCandles.isEmpty()) {
            log.warn("No 1m source candles found for rollup target: {} at {}", target.getSymbol().getSymbol(), start);
            return null;
        }

        // Aggregate 1m candles
        BigDecimal open = sourceCandles.get(0).getOpenPrice();
        BigDecimal close = sourceCandles.get(sourceCandles.size() - 1).getClosePrice();
        BigDecimal high = sourceCandles.stream().map(Ohlc1m::getHighPrice).max(Comparator.naturalOrder())
                .orElse(BigDecimal.ZERO);
        BigDecimal low = sourceCandles.stream().map(Ohlc1m::getLowPrice).min(Comparator.naturalOrder())
                .orElse(BigDecimal.ZERO);
        long volume = sourceCandles.stream().mapToLong(Ohlc1m::getVolume).sum();

        // Fetch existing higher timeframe candle
        Optional<? extends AbstractOhlc> existingOpt = fetchExisting(target);

        if (existingOpt.isPresent()) {
            AbstractOhlc existing = existingOpt.get();
            if (isSame(existing, open, high, low, close, volume)) {
                return null; // Skip if no change (Verify before Overwrite)
            }
            existing.apply(open, high, low, close, volume);
            return existing;
        } else {
            return createNew(target, open, high, low, close, volume);
        }
    }

    private Optional<? extends AbstractOhlc> fetchExisting(RollupTarget target) {
        Long symbolId = target.getSymbol().getId();
        LocalDateTime time = target.getBucketTime();
        if (target.getTargetInterval().equals("5m")) {
            return ohlc5mService.findBySymbolIdAndBucketTime(symbolId, time);
        } else {
            return ohlc30mService.findBySymbolIdAndBucketTime(symbolId, time);
        }
    }

    private AbstractOhlc createNew(RollupTarget target, BigDecimal o, BigDecimal h, BigDecimal l, BigDecimal c,
            long v) {
        if (target.getTargetInterval().equals("5m")) {
            Ohlc5m candle = Ohlc5m.builder().symbol(target.getSymbol()).bucketTime(target.getBucketTime()).build();
            candle.apply(o, h, l, c, v);
            return candle;
        } else {
            Ohlc30m candle = Ohlc30m.builder().symbol(target.getSymbol()).bucketTime(target.getBucketTime()).build();
            candle.apply(o, h, l, c, v);
            return candle;
        }
    }

    private boolean isSame(AbstractOhlc existing, BigDecimal o, BigDecimal h, BigDecimal l, BigDecimal c, long v) {
        return existing.getOpenPrice().compareTo(o) == 0 &&
                existing.getHighPrice().compareTo(h) == 0 &&
                existing.getLowPrice().compareTo(l) == 0 &&
                existing.getClosePrice().compareTo(c) == 0 &&
                existing.getVolume() == v;
    }
}
