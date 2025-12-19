package com.coinflow.process.service;

import com.coinflow.domain.ohlc.domain.Ohlc1m;
import com.coinflow.domain.ohlc.service.Ohlc1mService;
import com.coinflow.domain.ohlc.service.Ohlc5mService;
import com.coinflow.domain.ohlc.constant.OhlcInterval;
import com.coinflow.domain.symbol.domain.Symbol;
import com.coinflow.process.rollup.OhlcRollupCalculator;
import com.coinflow.process.util.TimeBucket;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Slf4j
public class Ohlc5mRollupService {

    private static final ZoneOffset UTC = ZoneOffset.UTC;
    private static final OhlcInterval TARGET_INTERVAL = OhlcInterval.M5;

    private final Ohlc1mService ohlc1mService;
    private final Ohlc5mService ohlc5mService;

    @Transactional
    public void rollupClosedBuckets() {
        LocalDateTime now = LocalDateTime.ofInstant(Instant.now(), UTC);
        LocalDateTime lastClosedBucket = calculateLastClosedBucket(now);

        rollupAndUpsert(lastClosedBucket);
    }

    private LocalDateTime calculateLastClosedBucket(LocalDateTime now) {
        LocalDateTime currentBucket = TimeBucket.to5m(now);

        if(now.isBefore(currentBucket.plusMinutes(5))) {
            return currentBucket.minusMinutes(5);
        }

        return currentBucket;
    }

    private void rollupAndUpsert(LocalDateTime bucketStart) {
        LocalDateTime endExclusive = bucketStart.plus(TARGET_INTERVAL.duration());

        List<Ohlc1m> candles =
                ohlc1mService.findCandlesInBucketRange(bucketStart, endExclusive);

        if (candles.isEmpty()) {
            return;
        }

        candles.stream()
                .collect(Collectors.groupingBy(c -> c.getSymbol().getId()))
                .values()
                .forEach(symbolCandles -> rollupSymbol(bucketStart, symbolCandles));
    }

    private void rollupSymbol(LocalDateTime bucketStart, List<Ohlc1m> symbolCandles) {
        Symbol symbol = symbolCandles.get(0).getSymbol();

        OhlcRollupCalculator
                .tryRollup(symbolCandles, TARGET_INTERVAL, bucketStart)
                .ifPresent(rollup -> {
                    log.info(
                            "5m rollup upsert. symbol={}, bucketStart={}, sourceCount={}",
                            symbol.getId(),
                            bucketStart,
                            symbolCandles.size()
                    );
                    ohlc5mService.upsert(symbol, bucketStart, rollup);
                });
    }
}
