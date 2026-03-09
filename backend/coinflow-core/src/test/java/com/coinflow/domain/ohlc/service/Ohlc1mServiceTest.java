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
        @DisplayName("Persistence: Apply Overwrite Data (SSOT from Aggregator)")
        void applyAndSave_Overwrite_Test() {
                // Given
                Long symbolId = 1L;
                Symbol symbol = Symbol.builder().id(symbolId).build();
                LocalDateTime bucketTime = LocalDateTime.of(2024, 1, 1, 12, 0, 0);

                // Existing DB Data
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

                // When: Aggregator sends updated final state via applyAndSave
                Ohlc1mService service = new Ohlc1mService(ohlc1mRepository);
                service.applyAndSave(symbol, bucketTime,
                                BigDecimal.valueOf(100), // Open
                                BigDecimal.valueOf(115), // High (updated)
                                BigDecimal.valueOf(90), // Low
                                BigDecimal.valueOf(112), // Close (updated)
                                1050L); // Volume (updated)

                // Then: The DB entity should be completely overwritten by the new request
                assertEquals(1050L, existingCandle.getVolume());
                assertEquals(BigDecimal.valueOf(115), existingCandle.getHighPrice());
                assertEquals(BigDecimal.valueOf(112), existingCandle.getClosePrice());
        }
}
