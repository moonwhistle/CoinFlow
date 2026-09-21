package com.coinflow.replay.batch.processor;

import com.coinflow.domain.ohlc.domain.Ohlc1m;
import com.coinflow.domain.ohlc.domain.Ohlc30m;
import com.coinflow.domain.ohlc.domain.Ohlc5m;
import com.coinflow.domain.ohlc.service.Ohlc1mService;
import com.coinflow.domain.recovery.service.RecoveryCandleStore;
import com.coinflow.domain.symbol.domain.Symbol;
import com.coinflow.replay.batch.model.RollupTarget;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class OhlcRollupProcessorTest {
    private final Ohlc1mService minute = mock(Ohlc1mService.class);
    private final RecoveryCandleStore verified = mock(RecoveryCandleStore.class);
    private final OhlcRollupProcessor processor = new OhlcRollupProcessor(minute, verified);
    private final Symbol symbol = Symbol.builder().id(1L).symbol("btcusdt").build();
    private final LocalDateTime start = LocalDateTime.of(2024, 3, 12, 10, 0);

    @Test void requiresEveryMinuteForThirtyMinuteRollup() {
        when(minute.findCandlesInBucketRange(any(), any(), any())).thenReturn(sources(5));
        assertNull(processor.process(new RollupTarget(symbol, start, 30)));
        verifyNoInteractions(verified);
    }

    @Test void requiresVerificationNotJustExistingRows() {
        when(minute.findCandlesInBucketRange(any(), any(), any())).thenReturn(sources(5));
        assertNull(processor.process(new RollupTarget(symbol, start, 5)));
    }

    @Test void rejectsGapEvenWhenCountMatches() {
        var rows = new java.util.ArrayList<>(sources(5));
        rows.set(2, sources(6).get(5));
        when(minute.findCandlesInBucketRange(any(), any(), any())).thenReturn(rows);
        when(verified.isVerified(anyString(), anyString(), anyLong())).thenReturn(true);
        assertNull(processor.process(new RollupTarget(symbol, start, 5)));
    }

    @Test void aggregatesCompleteVerifiedFiveAndThirtyMinuteWindows() {
        when(verified.isVerified(anyString(), anyString(), anyLong())).thenReturn(true);
        for (int count : List.of(5, 30)) {
            when(minute.findCandlesInBucketRange(any(), any(), any())).thenReturn(sources(count));
            var result = processor.process(new RollupTarget(symbol, start, count));
            assertNotNull(result);
            assertEquals(count == 5 ? Ohlc5m.class : Ohlc30m.class, result.getClass());
            assertEquals(100L * count, result.getVolume());
            assertEquals(BigDecimal.TEN, result.getClosePrice());
        }
    }

    private List<Ohlc1m> sources(int count) {
        return IntStream.range(0, count).mapToObj(i -> Ohlc1m.builder().symbol(symbol)
                .bucketTime(start.plusMinutes(i)).open(BigDecimal.ONE).high(BigDecimal.TEN)
                .low(BigDecimal.ONE).close(BigDecimal.TEN).volume(100L).build()).toList();
    }
}
