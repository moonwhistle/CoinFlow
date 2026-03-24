package com.coinflow.domain.aggregation.domain;
 
import com.coinflow.domain.aggregation.domain.MutableKlineSnapshot;
import com.coinflow.domain.aggregation.domain.vo.KlineSnapshot;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;

class MutableKlineSnapshotTest {

    @Test
    @DisplayName("applyLateTick: Verify OHLCV updates properly and Open is unchanged")
    void testApplyLateTick() {
        // Given
        KlineSnapshot initialSnapshot = new KlineSnapshot(
                1000L,
                1059L,
                new BigDecimal("100"), // open
                new BigDecimal("110"), // high
                new BigDecimal("90"), // low
                new BigDecimal("105"), // close
                new BigDecimal("500"), // volume
                10, // trades
                true // closed
        );

        MutableKlineSnapshot mutable = new MutableKlineSnapshot(initialSnapshot);

        // When - Apply a late tick that breaks the high
        mutable.applyLateTick(new BigDecimal("115"), 50_000_000_00L); // 50 * 10^8

        // Then
        KlineSnapshot afterFirst = mutable.toSnapshot();
        assertEquals(new BigDecimal("100"), afterFirst.open(), "Open must be unchanged");
        assertEquals(new BigDecimal("115"), afterFirst.high(), "High must update");
        assertEquals(new BigDecimal("90"), afterFirst.low(), "Low must be unchanged");
        assertEquals(new BigDecimal("115"), afterFirst.close(), "Close must update to latest tick price");
        assertEquals(0, new BigDecimal("550").compareTo(afterFirst.volume()), "Volume must accumulate");
        assertEquals(11, afterFirst.trades(), "Trade count must increase");

        // When - Apply a late tick that breaks the low
        mutable.applyLateTick(new BigDecimal("80"), 100_000_000_00L); // 100 * 10^8

        // Then
        KlineSnapshot afterSecond = mutable.toSnapshot();
        assertEquals(new BigDecimal("100"), afterSecond.open(), "Open must be unchanged");
        assertEquals(new BigDecimal("115"), afterSecond.high(), "High must be unchanged");
        assertEquals(new BigDecimal("80"), afterSecond.low(), "Low must update");
        assertEquals(new BigDecimal("80"), afterSecond.close(), "Close must update to latest tick price");
        assertEquals(0, new BigDecimal("650").compareTo(afterSecond.volume()), "Volume must accumulate");
        assertEquals(12, afterSecond.trades(), "Trade count must increase");
    }

    @Test
    @DisplayName("isExpired: TTL verification")
    void testIsExpired() throws InterruptedException {
        // Given
        KlineSnapshot initialSnapshot = new KlineSnapshot(
                1000L, 1059L, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, 0,
                true);
        MutableKlineSnapshot mutable = new MutableKlineSnapshot(initialSnapshot);

        // When/Then
        assertFalse(mutable.isExpired(5000L), "Should not be expired immediately");

        // Simulating expiration isn't easy without injecting a Clock, but we can verify
        // negative / zero TTL
        assertTrue(mutable.isExpired(-1L), "Should be expired if TTL is negative");
    }
}
