package com.coinflow.domain.ohlc.snapshot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

import com.coinflow.domain.ohlc.domain.Ohlc1m;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class OhlcCandleSnapshotTest {

    @Test
    @DisplayName("should scale down volume when creating snapshot from Ohlc1m")
    void shouldScaleDownVolume() {
        // given
        // Ohlc1m has volume 100,000,000 (scaled by 10^8) -> expected 1.0 real volume
        long scaledVolume = 100_000_000L;
        Ohlc1m mockCandle = mock(Ohlc1m.class);

        given(mockCandle.getBucketTime()).willReturn(LocalDateTime.now());
        given(mockCandle.getOpenPrice()).willReturn(BigDecimal.TEN);
        given(mockCandle.getHighPrice()).willReturn(BigDecimal.TEN);
        given(mockCandle.getLowPrice()).willReturn(BigDecimal.TEN);
        given(mockCandle.getClosePrice()).willReturn(BigDecimal.TEN);
        given(mockCandle.getVolume()).willReturn(scaledVolume);

        // when
        OhlcCandleSnapshot snapshot = OhlcCandleSnapshot.from(mockCandle);

        // then
        // VolumeScaler divides by 10^8
        // expected: 1.00000000 (scale 8) check value equality
        assertEquals(0, new BigDecimal("1.0").compareTo(snapshot.volume()));
    }
}
