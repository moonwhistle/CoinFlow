package com.coinflow.domain.ohlc.service;

import com.coinflow.domain.ohlc.domain.Ohlc1m;
import com.coinflow.domain.ohlc.repository.Ohlc1mRepository;
import com.coinflow.domain.ohlc.snapshot.OhlcRangeAggregate;
import com.coinflow.domain.ohlc.snapshot.OhlcRangeStatistics;
import com.coinflow.domain.symbol.domain.Symbol;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
public class Ohlc1mService {

        private final Ohlc1mRepository ohlc1mRepository;

        @Transactional
        public void applyAndSave(Symbol symbol, LocalDateTime bucketTime, BigDecimal open, BigDecimal high,
                        BigDecimal low,
                        BigDecimal close, long volume) {
                Optional<Ohlc1m> optionalCandle = ohlc1mRepository.findBySymbolIdAndBucketTime(symbol.getId(),
                                bucketTime);

                if (optionalCandle.isPresent()) {
                        Ohlc1m candle = optionalCandle.get();
                        candle.apply(open, high, low, close, volume);
                        ohlc1mRepository.save(candle);
                } else {
                        Ohlc1m candle = Ohlc1m.builder()
                                        .symbol(symbol)
                                        .bucketTime(bucketTime)
                                        .open(open)
                                        .high(high)
                                        .low(low)
                                        .close(close)
                                        .volume(volume)
                                        .build();
                        ohlc1mRepository.save(candle);
                }
        }

        @Transactional(readOnly = true)
        public List<Ohlc1m> findCandlesInBucketRange(Long symbolId, LocalDateTime startInclusive,
                        LocalDateTime endExclusive) {
                return ohlc1mRepository.findCandlesInBucketRange(symbolId, startInclusive, endExclusive);
        }

        @Transactional(readOnly = true)
        public Optional<Ohlc1m> findBySymbolIdAndBucketTime(Long symbolId, LocalDateTime bucketTime) {
                return ohlc1mRepository.findBySymbolIdAndBucketTime(symbolId, bucketTime);
        }

        @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
        public Optional<OhlcRangeStatistics> summarizeRange(
                        Long symbolId,
                        LocalDateTime startInclusive,
                        LocalDateTime endExclusive) {
                Optional<Ohlc1m> first = ohlc1mRepository
                                .findFirstBySymbolIdAndBucketTimeGreaterThanEqualAndBucketTimeLessThanOrderByBucketTimeAsc(
                                                symbolId, startInclusive, endExclusive);

                if (first.isEmpty()) {
                        return Optional.empty();
                }

                Ohlc1m last = ohlc1mRepository
                                .findFirstBySymbolIdAndBucketTimeGreaterThanEqualAndBucketTimeLessThanOrderByBucketTimeDesc(
                                                symbolId, startInclusive, endExclusive)
                                .orElseThrow();
                OhlcRangeAggregate aggregate = ohlc1mRepository.aggregateInBucketRange(
                                symbolId, startInclusive, endExclusive);

                return Optional.of(new OhlcRangeStatistics(
                                first.get().getBucketTime(),
                                last.getBucketTime(),
                                first.get().getOpenPrice(),
                                aggregate.highPrice(),
                                aggregate.lowPrice(),
                                last.getClosePrice(),
                                aggregate.volume()));
        }

        @Transactional
        public void saveAll(List<Ohlc1m> candles) {
                ohlc1mRepository.saveAll(candles);
        }
}
