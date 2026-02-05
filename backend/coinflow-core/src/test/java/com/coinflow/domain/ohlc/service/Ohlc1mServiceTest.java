package com.coinflow.domain.ohlc.service;

import com.coinflow.domain.ohlc.domain.Ohlc1m;
import com.coinflow.domain.ohlc.provider.RealTimeOhlcProvider;
import com.coinflow.domain.ohlc.repository.Ohlc1mRepository;
import com.coinflow.domain.symbol.domain.Symbol;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.BDDMockito.given;

@ExtendWith(MockitoExtension.class)
class Ohlc1mServiceTest {

    @Mock
    private Ohlc1mRepository ohlc1mRepository;

    @Mock
    private RealTimeOhlcProvider realTimeOhlcProvider;

    @Test
    @DisplayName("Merge: Historical data from DB + Real-time data from Memory")
    void findCandlesInBucketRange_Merge_Test() {
        // Given
        Long symbolId = 1L;
        Symbol symbol = Symbol.builder().id(symbolId).build();
        LocalDateTime start = LocalDateTime.of(2024, 1, 1, 12, 0, 0);
        LocalDateTime end = LocalDateTime.of(2024, 1, 1, 12, 5, 0);

        // 1. DB returns candles for 12:00 ~ 12:03 (4 candles)
        List<Ohlc1m> dbCandles = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            dbCandles.add(Ohlc1m.builder()
                    .symbol(symbol)
                    .bucketTime(start.plusMinutes(i))
                    .close(BigDecimal.valueOf(100 + i))
                    .build());
        }
        given(ohlc1mRepository.findCandlesInBucketRange(symbolId, start, end))
                .willReturn(dbCandles);

        // 2. RealTimeProvider returns candle for 12:04 (Current bucket)
        LocalDateTime lastBucketTime = end.minusMinutes(1); // 12:04
        Ohlc1m realTimeCandle = Ohlc1m.builder()
                .symbol(symbol)
                .bucketTime(lastBucketTime)
                .close(BigDecimal.valueOf(999)) // Very different price to distinguish
                .build();

        Ohlc1mService service = new Ohlc1mService(ohlc1mRepository, Optional.of(realTimeOhlcProvider));

        given(realTimeOhlcProvider.getRealTimeCandle(symbolId, lastBucketTime))
                .willReturn(Optional.of(realTimeCandle));

        // When
        List<Ohlc1m> result = service.findCandlesInBucketRange(symbolId, start, end);

        // Then
        assertEquals(5, result.size()); // 4 from DB + 1 from RealTime
        assertEquals(BigDecimal.valueOf(999), result.get(4).getClosePrice());
        assertEquals(lastBucketTime, result.get(4).getBucketTime());
    }

    @Test
    @DisplayName("Merge: Override DB data with fresher In-Memory data")
    void findCandlesInBucketRange_Merge_Override_Test() {
        // Given
        Long symbolId = 1L;
        Symbol symbol = Symbol.builder().id(symbolId).build();
        LocalDateTime start = LocalDateTime.of(2024, 1, 1, 12, 0, 0);
        LocalDateTime end = LocalDateTime.of(2024, 1, 1, 12, 5, 0);
        LocalDateTime lastBucketTime = end.minusMinutes(1); // 12:04

        // 1. DB returns stale data for 12:04 (e.g. flushed 30 seconds ago)
        List<Ohlc1m> dbCandles = new ArrayList<>();
        dbCandles
                .add(Ohlc1m.builder().symbol(symbol).bucketTime(lastBucketTime).close(BigDecimal.valueOf(100)).build());

        given(ohlc1mRepository.findCandlesInBucketRange(symbolId, start, end))
                .willReturn(dbCandles);

        // 2. RealTimeProvider returns fresher data for 12:04
        Ohlc1m realTimeCandle = Ohlc1m.builder()
                .symbol(symbol)
                .bucketTime(lastBucketTime)
                .close(BigDecimal.valueOf(200))
                .build();

        Ohlc1mService service = new Ohlc1mService(ohlc1mRepository, Optional.of(realTimeOhlcProvider));

        given(realTimeOhlcProvider.getRealTimeCandle(symbolId, lastBucketTime))
                .willReturn(Optional.of(realTimeCandle));

        // When
        List<Ohlc1m> result = service.findCandlesInBucketRange(symbolId, start, end);

        // Then
        assertEquals(1, result.size());
        assertEquals(BigDecimal.valueOf(200), result.get(0).getClosePrice()); // Should be overridden
    }
}
