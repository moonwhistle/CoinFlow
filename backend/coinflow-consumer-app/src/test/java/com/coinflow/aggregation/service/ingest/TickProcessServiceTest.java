package com.coinflow.aggregation.service.ingest;

import com.coinflow.aggregation.service.kline.KlineAggregator;
import com.coinflow.aggregation.service.kline.KlineAggregator.AggregationResult;
import com.coinflow.aggregation.service.kline.KlineAggregator.ClosedKlineSnapshot;
import com.coinflow.aggregation.service.kline.KlineState.KlineSnapshot;
import com.coinflow.aggregation.service.kline.KlineSnapshotBroadcaster;
import com.coinflow.aggregation.service.ticker.TickerBroadcaster;
import com.coinflow.aggregation.service.persist.DbPersistService;
import com.coinflow.tick.event.TickRawEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class TickProcessServiceTest {

    @Mock
    private KlineAggregator klineAggregator;
    @Mock
    private KlineSnapshotBroadcaster klineBroadcaster;
    @Mock
    private TickerBroadcaster tickerBroadcaster;
    @Mock
    private DbPersistService dbPersistService;

    @InjectMocks
    private TickProcessService tickProcessService;

    private TickRawEvent testEvent;

    @BeforeEach
    void setUp() {
        testEvent = new TickRawEvent("btcusdt", new BigDecimal("100"), new BigDecimal("10"), Instant.now(), "stream1");
    }

    @Test
    @DisplayName("Process tick generating LateUpdatedSnapshots")
    void processLateTickTest() {
        // Given
        KlineSnapshot lateSnapshot = new KlineSnapshot(100L, 159L, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                BigDecimal.ZERO, BigDecimal.ZERO, 0, true);
        ClosedKlineSnapshot closedKlineSnapshot = new ClosedKlineSnapshot("M1", lateSnapshot);

        AggregationResult result = new AggregationResult(
                List.of(), // No new closed
                List.of(), // No live for this test case (simplified)
                List.of(closedKlineSnapshot) // We have a late update!
        );

        when(klineAggregator.processTickAndGetResult(
                eq("btcusdt"), any(), any(), anyLong())).thenReturn(result);

        // When
        tickProcessService.process(testEvent);

        // Then
        // 1. Ticker must be broadcasted
        verify(tickerBroadcaster, times(1)).broadcast(any());
        // 2. Late snapshots must be broadcasted
        verify(klineBroadcaster, times(1)).broadcastAndSave("btcusdt", "M1", lateSnapshot);
        // 3. DbPersistService must be called asynchronously
        verify(dbPersistService, times(1)).persistClosedCandleAsync(eq("btcusdt"), eq(closedKlineSnapshot));
    }
}
