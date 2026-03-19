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
import org.mockito.Answers;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.core.RedisTemplate;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletableFuture;

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
    @Mock(answer = Answers.RETURNS_DEEP_STUBS)
    private RedisTemplate<String, String> redisTemplate;

    @InjectMocks
    private TickProcessService tickProcessService;

    private TickRawEvent testEvent;
    private RecordId recordId = RecordId.of("123-0");

    @BeforeEach
    void setUp() {
        testEvent = new TickRawEvent("btcusdt", new BigDecimal("100"), new BigDecimal("10"), Instant.now(), "123-0");
    }

    @Test
    @DisplayName("Process tick generating LateUpdatedSnapshots and verify ACK after async completes")
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

        // [중요] 비동기 저장이 완료되었다는 Future를 반환하도록 Mocking
        when(dbPersistService.persistClosedCandleAsync(any(), any()))
                .thenReturn(CompletableFuture.completedFuture(null));

        // When
        tickProcessService.process(testEvent, "mystream", "mygroup", recordId);

        // Then
        // 1. Ticker must be broadcasted
        verify(tickerBroadcaster, times(1)).broadcast(any());
        // 2. Late snapshots must be broadcasted
        verify(klineBroadcaster, times(1)).broadcastAndSave(eq("btcusdt"), eq("M1"), any());
        // 3. DbPersistService must be called asynchronously
        verify(dbPersistService, times(1)).persistClosedCandleAsync(eq("btcusdt"), eq(closedKlineSnapshot));
        // 4. Redis ACK must be called (after all futures complete)
        verify(redisTemplate.opsForStream()).acknowledge(eq("mystream"), eq("mygroup"), eq(recordId));
    }
}
