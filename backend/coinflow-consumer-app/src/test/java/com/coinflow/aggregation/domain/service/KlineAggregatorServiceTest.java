package com.coinflow.aggregation.domain.service;

import com.coinflow.aggregation.domain.model.dto.ClosedKlineSnapshot;
import com.coinflow.aggregation.domain.service.dto.AggregationResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import static org.junit.jupiter.api.Assertions.*;

class KlineAggregatorTest {

    private KlineAggregator aggregator;
    private final String symbol = "btcusdt";

    @BeforeEach
    void setUp() {
        aggregator = new KlineAggregator();
    }

    @Test
    @DisplayName("Normal tick updates live snapshots in multiple intervals")
    void testNormalTick() {
        // time = 1000s (bucket=1000, not bucket transition)
        AggregationResult result = aggregator.processTickAndGetResult(symbol, new BigDecimal("100"),
                new BigDecimal("10"), 1000_000L);

        // Ensure no closed or late ticks
        assertTrue(result.closedSnapshots().isEmpty());
        assertTrue(result.lateUpdatedSnapshots().isEmpty());

        // Ensure 3 live snapshots (M1, M5, M30)
        assertEquals(3, result.liveSnapshots().size());

        for (ClosedKlineSnapshot live : result.liveSnapshots()) {
            assertEquals(new BigDecimal("100"), live.snapshot().high(), "High should match tick price");
            assertFalse(live.snapshot().closed(), "Live should not be closed");
            if (live.interval().equals("M1")) {
                assertEquals(1000L - (1000L % 60L), live.snapshot().startTime(), "M1 bucket start");
            }
        }
    }

    @Test
    @DisplayName("Bucket transition yields closed snapshots and stores them in RecentlyClosedBuffer")
    void testBucketTransition() {
        // T1: 60s (tick in M1 60~119 bucket)
        aggregator.processTickAndGetResult(symbol, new BigDecimal("100"), new BigDecimal("10"), 60_000L);

        // T2: 121s (tick in M1 120~179 bucket). This triggers a close event for the 60s
        // bucket for M1 interval
        AggregationResult result = aggregator.processTickAndGetResult(symbol, new BigDecimal("105"),
                new BigDecimal("5"), 121_000L);

        // We should have exactly 1 closed snapshot for M1
        assertEquals(1, result.closedSnapshots().size());
        ClosedKlineSnapshot closedM1 = result.closedSnapshots().get(0);
        assertEquals("M1", closedM1.interval());
        assertEquals(60L, closedM1.snapshot().startTime());
        assertTrue(closedM1.snapshot().closed());
        assertEquals(new BigDecimal("100"), closedM1.snapshot().close(), "Close is 100 from the first tick");

        // M5 (300) and M30 (1800) did not change buckets, 60s and 121s are both in
        // bucket 0
        assertEquals(3, result.liveSnapshots().size()); // all 3 still have live
    }

    @Test
    @DisplayName("Late tick finds the buffer and returns lateUpdatedSnapshots")
    void testLateTick() {
        // 1. Send tick in 60s bucket
        aggregator.processTickAndGetResult(symbol, new BigDecimal("100"), new BigDecimal("10"), 60_000L);

        // 2. Send tick in 120s bucket -> Closes 60s bucket and stores to buffer
        aggregator.processTickAndGetResult(symbol, new BigDecimal("105"), new BigDecimal("5"), 121_000L);

        // 3. Send a 'late tick' that belongs to the 60s bucket (timestamp 119s)
        // Its price is 150 which breaks high, and quantity is 2
        AggregationResult lateResult = aggregator.processTickAndGetResult(symbol, new BigDecimal("150"),
                new BigDecimal("2"), 119_000L);

        // Should NOT trigger new closed snapshots
        assertTrue(lateResult.closedSnapshots().isEmpty());
        // M1 is not advanced, but M5/M30 are affected (live tick for bucket 0)

        // The most important part: We must generate a lateUpdatedSnapshot for M1
        assertEquals(1, lateResult.lateUpdatedSnapshots().size());
        ClosedKlineSnapshot lateM1 = lateResult.lateUpdatedSnapshots().get(0);

        assertEquals("M1", lateM1.interval());
        assertEquals(60L, lateM1.snapshot().startTime());
        assertEquals(new BigDecimal("150"), lateM1.snapshot().high(), "High should be broken by late tick");
        assertEquals(new BigDecimal("150"), lateM1.snapshot().close(), "Close is latest tick in applyLateTick");

        // Scaled volume for 10 is 10*10^8, quantity 2 is 2*10^8
        // 10 + 2 = 12 total
        assertEquals(new BigDecimal("12.00000000").stripTrailingZeros(),
                lateM1.snapshot().volume().stripTrailingZeros());
        assertEquals(2, lateM1.snapshot().trades(), "First tick + late tick = 2 trades");
    }

    @Test
    @DisplayName("Late tick for a non-existent or expired bucket is discarded")
    void testExpiredLateTick() {
        // T: 6000s, advancing bucket quite far
        aggregator.processTickAndGetResult(symbol, new BigDecimal("100"), new BigDecimal("10"), 6000_000L);

        // Send late tick for an incredibly old bucket (0s bucket)
        // Since the 0s bucket was never opened or closed by *this* instance, it
        // shouldn't exist
        AggregationResult result = aggregator.processTickAndGetResult(symbol, new BigDecimal("150"),
                new BigDecimal("10"), 10_000L);

        assertTrue(result.lateUpdatedSnapshots().isEmpty(), "Late tick should be ignored because there's no buffer");
    }
}
