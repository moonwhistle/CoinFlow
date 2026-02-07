package com.coinflow.aggregation.service.rollup.executor;

import com.coinflow.aggregation.process.time.BucketCloseChecker;
import com.coinflow.aggregation.service.event.CandleClosedEventPublisher;
import com.coinflow.aggregation.service.rollup.upserter.OhlcRollupUpserter;
import com.coinflow.aggregation.service.rollup.upserter.OhlcRollupUpserterRegistry;
import com.coinflow.domain.ohlc.constant.OhlcInterval;
import com.coinflow.domain.ohlc.domain.Ohlc1m;
import com.coinflow.aggregation.process.rollup.OhlcRollup;
import com.coinflow.domain.ohlc.service.Ohlc1mService;
import com.coinflow.domain.symbol.domain.Symbol;
import com.coinflow.domain.symbol.service.SymbolService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.times;

@ExtendWith(MockitoExtension.class)
class OhlcRollupExecutorTest {

    @Mock
    private Ohlc1mService ohlc1mService;
    @Mock
    private SymbolService symbolService;
    @Mock
    private BucketCloseChecker bucketCloseChecker;
    @Mock
    private OhlcRollupUpserterRegistry upserterRegistry;
    @Mock
    private CandleClosedEventPublisher eventPublisher;
    @Mock
    private OhlcRollupUpserter upserter;

    @InjectMocks
    private OhlcRollupExecutor executor;

    @Test
    void rollupFrom1mIfClosed_ShouldPublishEvent_WhenBucketIsClosedAndCandlesExist() {
        // Given
        Long symbolId = 1L;
        OhlcInterval interval = OhlcInterval.M5;
        LocalDateTime bucketStart = LocalDateTime.of(2023, 1, 1, 0, 0);
        Symbol symbol = Symbol.builder().id(symbolId).symbol("BTCUSDT").build();

        given(bucketCloseChecker.isOpen(interval, bucketStart)).willReturn(false);
        given(ohlc1mService.findCandlesInBucketRange(eq(symbolId), eq(bucketStart), any())).willReturn(List.of(
                Ohlc1m.builder()
                        .open(new BigDecimal("100"))
                        .high(new BigDecimal("110"))
                        .low(new BigDecimal("90"))
                        .close(new BigDecimal("105"))
                        .volume(10L)
                        .build()));
        given(symbolService.findSymbol(symbolId)).willReturn(symbol);
        given(upserterRegistry.get(interval)).willReturn(upserter);

        // When
        executor.rollupFrom1mIfClosed(symbolId, interval, bucketStart);

        // Then
        verify(upserter).upsert(eq(symbolId), eq(symbol), eq(bucketStart), any(OhlcRollup.class));
        verify(eventPublisher).publish(
                eq(symbolId),
                eq("BTCUSDT"),
                eq("M5"),
                eq(bucketStart.toString()),
                eq(new BigDecimal("100")),
                eq(new BigDecimal("110")),
                eq(new BigDecimal("90")),
                eq(new BigDecimal("105")),
                eq(10L));
    }

    @Test
    void rollupFrom1mIfClosed_ShouldNotPublishEvent_WhenBucketIsOpen() {
        // Given
        Long symbolId = 1L;
        OhlcInterval interval = OhlcInterval.M5;
        LocalDateTime bucketStart = LocalDateTime.of(2023, 1, 1, 0, 0);

        given(bucketCloseChecker.isOpen(interval, bucketStart)).willReturn(true);

        // When
        executor.rollupFrom1mIfClosed(symbolId, interval, bucketStart);

        // Then
        verify(eventPublisher, times(0)).publish(any(), any(), any(), any(), any(), any(), any(), any(), any());
    }
}
