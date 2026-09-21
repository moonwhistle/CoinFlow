package com.coinflow.replay.batch.processor;

import com.coinflow.domain.ohlc.domain.AbstractOhlc;
import com.coinflow.domain.ohlc.domain.Ohlc1m;
import com.coinflow.domain.ohlc.domain.Ohlc5m;
import com.coinflow.domain.ohlc.domain.Ohlc30m;
import com.coinflow.domain.ohlc.service.Ohlc1mService;
import com.coinflow.domain.recovery.service.RecoveryCandleStore;
import com.coinflow.replay.batch.model.RollupTarget;
import java.math.BigDecimal;
import java.time.ZoneOffset;
import java.util.Comparator;
import lombok.RequiredArgsConstructor;
import org.springframework.batch.item.ItemProcessor;

@RequiredArgsConstructor
public class OhlcRollupProcessor implements ItemProcessor<RollupTarget, AbstractOhlc> {
    private final Ohlc1mService minute;
    private final RecoveryCandleStore verified;

    @Override
    public AbstractOhlc process(RollupTarget target) {
        var start = target.getBucketTime();
        int count = target.getIntervalMinutes();
        if (count != 5 && count != 30) throw new IllegalArgumentException("Unsupported rollup interval");
        var sources = minute.findCandlesInBucketRange(target.getSymbol().getId(), start, start.plusMinutes(count));
        if (sources.size() != count) return null;
        for (int i = 0; i < count; i++) {
            if (!sources.get(i).getBucketTime().equals(start.plusMinutes(i))
                    || !verified.isVerified(target.getSymbol().getSymbol(), "M1",
                    start.plusMinutes(i).toEpochSecond(ZoneOffset.UTC))) return null;
        }
        BigDecimal high = sources.stream().map(Ohlc1m::getHighPrice).max(Comparator.naturalOrder()).orElseThrow();
        BigDecimal low = sources.stream().map(Ohlc1m::getLowPrice).min(Comparator.naturalOrder()).orElseThrow();
        long volume = sources.stream().mapToLong(Ohlc1m::getVolume).reduce(0, Math::addExact);
        AbstractOhlc result = count == 5
                ? Ohlc5m.builder().symbol(target.getSymbol()).bucketTime(start).build()
                : Ohlc30m.builder().symbol(target.getSymbol()).bucketTime(start).build();
        result.apply(sources.get(0).getOpenPrice(), high, low, sources.get(count - 1).getClosePrice(), volume);
        return result;
    }
}
