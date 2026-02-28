package com.coinflow.domain.ohlc.service;

import com.coinflow.domain.ohlc.domain.Ohlc1m;
import com.coinflow.domain.ohlc.repository.Ohlc1mRepository;
import com.coinflow.domain.symbol.domain.Symbol;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.BDDMockito.given;

@ExtendWith(MockitoExtension.class)
class Ohlc1mServiceTest {

        @Mock
        private Ohlc1mRepository ohlc1mRepository;

        @Test
        @DisplayName("Persistence: Merge Late Arrival Data (Accumulate Volume)")
        void applyAndSave_Merge_Test() {
                // Given
                Long symbolId = 1L;
                Symbol symbol = Symbol.builder().id(symbolId).build();
                LocalDateTime bucketTime = LocalDateTime.of(2024, 1, 1, 12, 0, 0);

                // Existing DB Data: Vol 1000
                Ohlc1m existingCandle = Ohlc1m.builder()
                                .symbol(symbol)
                                .bucketTime(bucketTime)
                                .open(BigDecimal.valueOf(100))
                                .high(BigDecimal.valueOf(110))
                                .low(BigDecimal.valueOf(90))
                                .close(BigDecimal.valueOf(105))
                                .volume(1000L)
                                .build();

                given(ohlc1mRepository.findBySymbolIdAndBucketTime(symbolId, bucketTime))
                                .willReturn(Optional.of(existingCandle));

                // When: Late tick flush attempts to save partial data (Vol 10)
                // Service should merge 10 into 1000 => 1010
                Ohlc1mService service = new Ohlc1mService(ohlc1mRepository);
                service.applyAndSave(symbol, bucketTime,
                                BigDecimal.valueOf(105), // Open (ignored in merge)
                                BigDecimal.valueOf(105), // High (lower than DB high, ignored)
                                BigDecimal.valueOf(105), // Low (higher than DB low, ignored)
                                BigDecimal.valueOf(105), // Close (ignored in merge)
                                10L);

                // Then
                assertEquals(1010L, existingCandle.getVolume());
                assertEquals(BigDecimal.valueOf(110), existingCandle.getHighPrice()); // Kept existing high
        }
}
