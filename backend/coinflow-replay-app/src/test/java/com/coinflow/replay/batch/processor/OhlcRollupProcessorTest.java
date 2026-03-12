package com.coinflow.replay.batch.processor;

import com.coinflow.domain.ohlc.domain.AbstractOhlc;
import com.coinflow.domain.ohlc.domain.Ohlc1m;
import com.coinflow.domain.ohlc.domain.Ohlc5m;
import com.coinflow.domain.ohlc.service.Ohlc1mService;
import com.coinflow.domain.ohlc.service.Ohlc30mService;
import com.coinflow.domain.ohlc.service.Ohlc5mService;
import com.coinflow.domain.symbol.domain.Symbol;
import com.coinflow.replay.batch.common.ReconciliationBatchConstants;
import com.coinflow.replay.batch.model.RollupTarget;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class OhlcRollupProcessorTest {

    private OhlcRollupProcessor processor;

    @Mock
    private Ohlc1mService ohlc1mService;

    @Mock
    private Ohlc5mService ohlc5mService;

    @Mock
    private Ohlc30mService ohlc30mService;

    private Symbol mockSymbol;
    private final String symbolName = "btcusdt";

    @BeforeEach
    void setUp() {
        processor = new OhlcRollupProcessor(ohlc1mService, ohlc5mService, ohlc30mService);
        mockSymbol = Symbol.builder()
                .id(1L)
                .symbol(symbolName)
                .build();
    }

    @Test
    @DisplayName("5분봉 롤업: 1분봉 데이터 5개를 합쳐서 새로운 Ohlc5m을 생성한다")
    void process_CreateNewOhlc5m() {
        // given
        LocalDateTime start = LocalDateTime.of(2024, 3, 12, 10, 0);
        RollupTarget target = new RollupTarget(mockSymbol, start, ReconciliationBatchConstants.INTERVAL_5M_MINUTES);

        List<Ohlc1m> sources = List.of(
                createOhlc1m(start, "100", "110", "95", "105", 1000L),
                createOhlc1m(start.plusMinutes(1), "105", "115", "100", "110", 1100L),
                createOhlc1m(start.plusMinutes(2), "110", "120", "105", "115", 1200L),
                createOhlc1m(start.plusMinutes(3), "115", "125", "110", "120", 1300L),
                createOhlc1m(start.plusMinutes(4), "120", "130", "115", "125", 1400L));

        when(ohlc1mService.findCandlesInBucketRange(eq(mockSymbol.getId()), eq(start), any())).thenReturn(sources);
        when(ohlc5mService.findBySymbolIdAndBucketTime(mockSymbol.getId(), start)).thenReturn(Optional.empty());

        // when
        AbstractOhlc result = processor.process(target);

        // then
        assertThat(result).isInstanceOf(Ohlc5m.class);
        assertThat(result.getOpenPrice()).isEqualByComparingTo("100");
        assertThat(result.getHighPrice()).isEqualByComparingTo("130");
        assertThat(result.getLowPrice()).isEqualByComparingTo("95");
        assertThat(result.getClosePrice()).isEqualByComparingTo("125");
        assertThat(result.getVolume()).isEqualTo(6000L);
    }

    @Test
    @DisplayName("30분봉 롤업: 1분봉 데이터들을 합쳐서 새로운 Ohlc30m을 생성한다")
    void process_CreateNewOhlc30m() {
        // given
        LocalDateTime start = LocalDateTime.of(2024, 3, 12, 10, 0);
        RollupTarget target = new RollupTarget(mockSymbol, start, ReconciliationBatchConstants.INTERVAL_30M_MINUTES);

        List<Ohlc1m> sources = List.of(
                createOhlc1m(start, "100", "110", "95", "105", 1000L),
                createOhlc1m(start.plusMinutes(29), "200", "210", "195", "205", 2000L));

        when(ohlc1mService.findCandlesInBucketRange(eq(mockSymbol.getId()), eq(start), any())).thenReturn(sources);
        when(ohlc30mService.findBySymbolIdAndBucketTime(mockSymbol.getId(), start)).thenReturn(Optional.empty());

        // when
        AbstractOhlc result = processor.process(target);

        // then
        assertThat(result).isInstanceOf(com.coinflow.domain.ohlc.domain.Ohlc30m.class);
        assertThat(result.getOpenPrice()).isEqualByComparingTo("100");
        assertThat(result.getClosePrice()).isEqualByComparingTo("205");
        assertThat(result.getVolume()).isEqualTo(3000L);
    }

    @Test
    @DisplayName("동일 데이터 스킵: 기존 데이터가 보정 결과와 완벽히 일치하면 null을 반환한다")
    void process_SkipWhenMatched() {
        // given
        LocalDateTime start = LocalDateTime.of(2024, 3, 12, 10, 0);
        RollupTarget target = new RollupTarget(mockSymbol, start, ReconciliationBatchConstants.INTERVAL_5M_MINUTES);

        List<Ohlc1m> sources = List.of(
                createOhlc1m(start, "100", "110", "95", "105", 1000L));

        Ohlc5m existing = Ohlc5m.builder()
                .symbol(mockSymbol).bucketTime(start).build();
        existing.apply(new BigDecimal("100"), new BigDecimal("110"), new BigDecimal("95"), new BigDecimal("105"),
                1000L);

        when(ohlc1mService.findCandlesInBucketRange(mockSymbol.getId(), start, any())).thenReturn(sources);
        when(ohlc5mService.findBySymbolIdAndBucketTime(mockSymbol.getId(), start)).thenReturn(Optional.of(existing));

        // when
        AbstractOhlc result = processor.process(target);

        // then
        assertThat(result).isNull();
    }

    @Test
    @DisplayName("소스 부재: 1분봉 데이터가 하나도 없으면 null을 반환한다")
    void process_ReturnNullWhenNoSource() {
        // given
        LocalDateTime start = LocalDateTime.of(2024, 3, 12, 10, 0);
        RollupTarget target = new RollupTarget(mockSymbol, start, ReconciliationBatchConstants.INTERVAL_5M_MINUTES);

        when(ohlc1mService.findCandlesInBucketRange(eq(mockSymbol.getId()), eq(start), any()))
                .thenReturn(Collections.emptyList());

        // when
        AbstractOhlc result = processor.process(target);

        // then
        assertThat(result).isNull();
        verify(ohlc5mService, never()).findBySymbolIdAndBucketTime(any(), any());
    }

    private Ohlc1m createOhlc1m(LocalDateTime time, String o, String h, String l, String c, long v) {
        return Ohlc1m.builder()
                .symbol(mockSymbol)
                .bucketTime(time)
                .open(new BigDecimal(o))
                .high(new BigDecimal(h))
                .low(new BigDecimal(l))
                .close(new BigDecimal(c))
                .volume(v)
                .build();
    }
}
