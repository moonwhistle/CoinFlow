package com.coinflow.domain.recovery.service;

import com.coinflow.domain.recovery.domain.VerifiedCandle;
import com.coinflow.domain.recovery.repository.VerifiedCandleRepository;

import com.coinflow.domain.aggregation.domain.vo.ClosedKlineSnapshot;
import com.coinflow.domain.ohlc.domain.*;
import com.coinflow.domain.ohlc.policy.VolumeScaler;
import com.coinflow.domain.ohlc.service.*;
import com.coinflow.domain.symbol.domain.Symbol;
import com.coinflow.domain.symbol.service.SymbolService;
import java.math.BigDecimal;
import java.time.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class RecoveryCandleStore {
    private final RecoveryLedger ledger;
    private final VerifiedCandleRepository verified;
    private final SymbolService symbols;
    private final Ohlc1mService minute;
    private final Ohlc5mService five;
    private final Ohlc30mService thirty;

    public boolean isVerified(String symbol, String interval, long bucket) {
        return verified.existsById(VerifiedCandle.key(symbol, interval, bucket));
    }

    public void saveConsumer(String symbol, ClosedKlineSnapshot candle) {
        ledger.locked(checkpoint -> {
            var s = candle.snapshot();
            if (!isVerified(symbol, candle.interval(), s.startTime())) {
                save(symbols.findBySymbol(symbol), candle.interval(),
                        LocalDateTime.ofEpochSecond(s.startTime(), 0, ZoneOffset.UTC),
                        s.open(), s.high(), s.low(), s.close(), VolumeScaler.toLong(s.volume()));
            }
            return null;
        });
    }

    public void saveVerified(AbstractOhlc candle) {
        ledger.locked(checkpoint -> {
            String interval = candle instanceof Ohlc1m ? "M1" : candle instanceof Ohlc5m ? "M5" : "M30";
            String symbol = candle.getSymbol().getSymbol();
            long bucket = candle.getBucketTime().toEpochSecond(ZoneOffset.UTC);
            if (!interval.equals("M1")) {
                int minutes = interval.equals("M5") ? 5 : 30;
                for (int i = 0; i < minutes; i++) {
                    if (!isVerified(symbol, "M1", bucket + i * 60L)) {
                        throw new IllegalStateException("Unverified rollup source: " + symbol + ":" + bucket);
                    }
                }
            }
            save(candle.getSymbol(), interval, candle.getBucketTime(), candle.getOpenPrice(),
                    candle.getHighPrice(), candle.getLowPrice(), candle.getClosePrice(), candle.getVolume());
            VerifiedCandle marker = new VerifiedCandle();
            marker.setId(VerifiedCandle.key(symbol, interval, bucket));
            marker.setSymbol(symbol);
            marker.setIntervalName(interval);
            marker.setBucket(bucket);
            marker.setVerifiedAt(System.currentTimeMillis());
            marker.setCachePublished(false);
            verified.save(marker);
            return null;
        });
    }

    private void save(Symbol symbol, String interval, LocalDateTime bucket, BigDecimal o,
            BigDecimal h, BigDecimal l, BigDecimal c, long v) {
        switch (interval) {
            case "M1" -> minute.applyAndSave(symbol, bucket, o, h, l, c, v);
            case "M5" -> five.applyAndSave(symbol, bucket, o, h, l, c, v);
            case "M30" -> thirty.applyAndSave(symbol, bucket, o, h, l, c, v);
            default -> throw new IllegalArgumentException("Unsupported interval: " + interval);
        }
    }
}
