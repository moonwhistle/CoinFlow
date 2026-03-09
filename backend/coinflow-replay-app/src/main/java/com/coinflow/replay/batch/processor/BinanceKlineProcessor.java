package com.coinflow.replay.batch.processor;

import com.coinflow.domain.log.domain.MissingTickLog;
import com.coinflow.domain.log.domain.vo.ReconciliationReason;
import com.coinflow.domain.log.service.MissingTickLogService;
import com.coinflow.domain.ohlc.domain.Ohlc1m;
import com.coinflow.domain.ohlc.service.Ohlc1mService;
import com.coinflow.domain.symbol.domain.Symbol;
import com.coinflow.domain.symbol.service.SymbolService;
import com.coinflow.replay.client.dto.BinanceKline;
import com.coinflow.replay.common.exception.ReplayErrorCode;
import com.coinflow.replay.common.exception.ReplayException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.item.ItemProcessor;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Optional;

public class BinanceKlineProcessor implements ItemProcessor<BinanceKline, Ohlc1m> {
    private static final Logger log = LoggerFactory.getLogger(BinanceKlineProcessor.class);
    private static final BigDecimal TOLERANCE_PERCENT = new BigDecimal("0.0001"); // 0.01% 오차범위 수용

    private final String symbolName;
    private final String intervalType;
    private final SymbolService symbolService;
    private final Ohlc1mService ohlc1mService;
    private final MissingTickLogService missingTickLogService;

    private Symbol cachedSymbol;

    public BinanceKlineProcessor(String symbolName, String intervalType,
            SymbolService symbolService,
            Ohlc1mService ohlc1mService,
            MissingTickLogService missingTickLogService) {
        if (symbolName == null || symbolName.trim().isEmpty()) {
            throw new ReplayException(ReplayErrorCode.INVALID_BATCH_PARAMETER);
        }
        if (intervalType == null || intervalType.trim().isEmpty()) {
            throw new ReplayException(ReplayErrorCode.INVALID_BATCH_PARAMETER);
        }

        this.symbolName = symbolName;
        this.intervalType = intervalType;
        this.symbolService = symbolService;
        this.ohlc1mService = ohlc1mService;
        this.missingTickLogService = missingTickLogService;
    }

    @Override
    public Ohlc1m process(BinanceKline item) {
        if (cachedSymbol == null) {
            cachedSymbol = symbolService.findBySymbol(symbolName);
        }

        LocalDateTime bucketTime = LocalDateTime.ofInstant(Instant.ofEpochMilli(item.openTime()),
                ZoneId.systemDefault());
        Optional<Ohlc1m> existingOhlcOpt = ohlc1mService.findBySymbolIdAndBucketTime(cachedSymbol.getId(),
                bucketTime);

        // Case 1: 완전 누락 (Missing)
        if (existingOhlcOpt.isEmpty()) {
            log.warn("Missing 1m candle found for {} at {}", symbolName, bucketTime);
            Ohlc1m newOhlc = Ohlc1m.builder()
                    .symbol(cachedSymbol)
                    .bucketTime(bucketTime)
                    .open(item.open())
                    .high(item.high())
                    .low(item.low())
                    .close(item.close())
                    .volume(item.volume().longValue())
                    .build();

            saveMissingTickLog(bucketTime, ReconciliationReason.MISSING, item.close(), null);
            return newOhlc;
        }

        Ohlc1m existingOhlc = existingOhlcOpt.get();

        // Case 2: 불일치 발생 (Mismatch)
        if (isMismatch(existingOhlc, item)) {
            log.warn("Mismatched 1m candle found for {} at {}. Updating to Binance values.", symbolName, bucketTime);
            saveMissingTickLog(bucketTime, ReconciliationReason.MISMATCH, item.close(),
                    existingOhlc.getClosePrice());

            existingOhlc.apply(item.open(), item.high(), item.low(), item.close(), item.volume().longValue());
            return existingOhlc;
        }

        // Case 3: 정상 (정합성 완벽 일치, DB 조작 생략)
        return null; // Writer에 아무것도 넘기지 않음
    }

    private boolean isMismatch(Ohlc1m existing, BinanceKline binance) {
        return !isWithinTolerance(existing.getOpenPrice(), binance.open())
                || !isWithinTolerance(existing.getHighPrice(), binance.high())
                || !isWithinTolerance(existing.getLowPrice(), binance.low())
                || !isWithinTolerance(existing.getClosePrice(), binance.close())
                || existing.getVolume().longValue() != binance.volume().longValue(); // 볼륨은 오차 없이 정수 형태 동등 비교
    }

    private boolean isWithinTolerance(BigDecimal dbValue, BigDecimal binanceValue) {
        if (dbValue.compareTo(binanceValue) == 0) {
            return true;
        }

        // 차이 계산 및 오차 한계 파악
        BigDecimal diff = dbValue.subtract(binanceValue).abs();
        BigDecimal tolerance = binanceValue.multiply(TOLERANCE_PERCENT).abs();

        return diff.compareTo(tolerance) <= 0;
    }

    private void saveMissingTickLog(LocalDateTime bucketTime, ReconciliationReason reason,
            BigDecimal expectedClose, BigDecimal actualClose) {
        MissingTickLog logEntry = MissingTickLog.builder()
                .symbol(cachedSymbol)
                .intervalType(intervalType)
                .bucketTime(bucketTime)
                .reason(reason)
                .expectedClosePrice(expectedClose)
                .actualClosePrice(actualClose)
                .build();

        missingTickLogService.save(logEntry);
    }
}
