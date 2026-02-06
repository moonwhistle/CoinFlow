package com.coinflow.domain.ohlc.service;

import com.coinflow.domain.ohlc.domain.Ohlc1m;
import com.coinflow.domain.ohlc.provider.RealTimeOhlcProvider;
import com.coinflow.domain.ohlc.repository.Ohlc1mRepository;
import com.coinflow.domain.symbol.domain.Symbol;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class Ohlc1mService {

        private final Ohlc1mRepository ohlc1mRepository;
        private final Optional<RealTimeOhlcProvider> realTimeOhlcProvider;

        @Transactional
        public void applyAndSave(Symbol symbol, LocalDateTime bucketTime, BigDecimal open, BigDecimal high,
                        BigDecimal low,
                        BigDecimal close, long volume) {
                Optional<Ohlc1m> optionalCandle = ohlc1mRepository.findBySymbolIdAndBucketTime(symbol.getId(),
                                bucketTime);

                if (optionalCandle.isPresent()) {
                        // Existing candle: Merge (Accumulate volume, Expand High/Low)
                        Ohlc1m candle = optionalCandle.get();
                        candle.merge(open, high, low, close, volume);
                        ohlc1mRepository.save(candle);
                } else {
                        // New candle: Create directly
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
                List<Ohlc1m> candles = ohlc1mRepository.findCandlesInBucketRange(symbolId, startInclusive,
                                endExclusive);

                // 마지막 버킷(endExclusive 직전)이 현재 진행 중인 시간인지 확인하고, 그렇다면 실시간 데이터를 병합
                // 여기서는 endExclusive 바로 전 버킷을 현재 버킷으로 가정함 (요청자가 올바른 범위를 보낸다고 가정)
                // 예를 들어 요청이 12:00 ~ 12:05 라면, 12:04분의 데이터가 실시간일 수 있음.
                // 하지만 보편적으로는 '현재 시간'을 기준으로 판단해야 함.
                // 단순화를 위해 endExclusive - 1분 위치를 RealTime 조회 대상으로 삼음. (1분 봉 기준)
                LocalDateTime lastBucketTime = endExclusive.minusMinutes(1);

                realTimeOhlcProvider.ifPresent(provider -> {
                        provider.getRealTimeCandle(symbolId, lastBucketTime).ifPresent(realTimeCandle -> {
                                // 이미 DB에서 가져온 리스트에 포함되어 있는지 확인 (Flush가 1초마다 되므로 있을 수도 있음)
                                // 만약 있다면 최신 메모리 상태로 덮어쓰기 로직 필요, 혹은 이미 완벽하다면 Skip
                                // 여기서는 간단히 리스트에 없으면 추가, 있으면 교체하는 식으로 구현
                                boolean exists = false;
                                for (int i = 0; i < candles.size(); i++) {
                                        if (candles.get(i).getBucketTime().equals(lastBucketTime)) {
                                                candles.set(i, realTimeCandle); // 교체
                                                exists = true;
                                                break;
                                        }
                                }
                                if (!exists) {
                                        candles.add(realTimeCandle);
                                }
                        });
                });

                return candles;
        }
}
