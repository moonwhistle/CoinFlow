package com.coinflow.domain.ohlc.service;

import com.coinflow.domain.ohlc.domain.Ohlc1m;
import com.coinflow.domain.ohlc.provider.RealTimeOhlcProvider;
import com.coinflow.domain.ohlc.repository.Ohlc1mRepository;
import com.coinflow.domain.symbol.domain.Symbol;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;

@ExtendWith(MockitoExtension.class)
class Ohlc1mServiceTest {

    @InjectMocks
    private Ohlc1mService ohlc1mService;

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

        // Need to wrap RealTimeOhlcProvider in Optional as it is injected as
        // Optional<RealTimeOhlcProvider>
        // But @Mock creates the instance, we need to handle the Optional in the
        // service.
        // Wait, @InjectMocks injects the Mock directly if the field is the interface?
        // In the Service it is Optional<RealTimeOhlcProvider>.
        // @InjectMocks might fail to inject into Optional field automatically.
        // We might need to manually construct the service or adjust the test.
        // Let's rely on constructor injection manually for safer test.
    }
}
