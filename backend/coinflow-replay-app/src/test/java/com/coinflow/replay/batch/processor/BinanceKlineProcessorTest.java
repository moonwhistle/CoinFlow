package com.coinflow.replay.batch.processor;

import com.coinflow.domain.log.domain.vo.ReconciliationReason;
import com.coinflow.domain.ohlc.domain.Ohlc1m;
import com.coinflow.domain.ohlc.policy.VolumeScaler;
import com.coinflow.domain.ohlc.service.Ohlc1mService;
import com.coinflow.domain.symbol.domain.Symbol;
import com.coinflow.domain.symbol.service.SymbolService;
import com.coinflow.replay.batch.common.ReconciliationBatchConstants;
import com.coinflow.replay.client.dto.BinanceKline;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BinanceKlineProcessorTest {

    private BinanceKlineProcessor processor;

    @Mock
    private SymbolService symbolService;

    @Mock
    private Ohlc1mService ohlc1mService;

    private final String symbol = "btcusdt";
    private final String interval = "1m";
    private Symbol mockSymbol;

    @BeforeEach
    void setUp() {
        processor = new BinanceKlineProcessor(symbol, interval, symbolService, ohlc1mService);
        mockSymbol = Symbol.builder()
                .symbol(symbol)
                .build();
    }

    @Test
    @DisplayName("Case 1: DB에 데이터가 아예 없는 경우 새 Ohlc1m 생성을 결정한다")
    void process_MissingData_ReturnsNewOhlc() {
        // given
        long timestamp = 1710000000000L;
        BinanceKline binanceKline = createBinanceKline(timestamp, "100", "110", "90", "105", "1000");

        when(symbolService.findBySymbol(symbol)).thenReturn(mockSymbol);
        when(ohlc1mService.findBySymbolIdAndBucketTime(any(), any())).thenReturn(Optional.empty());

        // when
        ReconciliationResult result = processor.process(binanceKline);

        // then
        assertThat(result).isNotNull();
        assertThat(result.ohlc1m()).isNotNull();
        assertThat(result.ohlc1m().getOpenPrice()).isEqualByComparingTo("100");
        assertThat(result.missingTickLog()).isNotNull();
        assertThat(result.missingTickLog().getReason()).isEqualTo(ReconciliationReason.MISSING);
    }

    @Test
    @DisplayName("Case 2: DB 데이터와 바이낸스 데이터가 불일치할 경우 수정을 결정한다")
    void process_MismatchedData_ReturnsUpdatedOhlc() {
        // given
        long timestamp = 1710000000000L;
        BinanceKline binanceKline = createBinanceKline(timestamp, "100", "110", "90", "105", "1000");

        LocalDateTime bucketTime = ReconciliationBatchConstants.toLocalDateTime(timestamp);
        Ohlc1m existingOhlc = Ohlc1m.builder()
                .symbol(mockSymbol)
                .bucketTime(bucketTime)
                .open(new BigDecimal("99")) // Mismatch
                .high(new BigDecimal("110"))
                .low(new BigDecimal("90"))
                .close(new BigDecimal("105"))
                .volume(VolumeScaler.toLong(new BigDecimal("1000")))
                .build();

        when(symbolService.findBySymbol(symbol)).thenReturn(mockSymbol);
        when(ohlc1mService.findBySymbolIdAndBucketTime(any(), eq(bucketTime))).thenReturn(Optional.of(existingOhlc));

        // when
        ReconciliationResult result = processor.process(binanceKline);

        // then
        assertThat(result).isNotNull();
        assertThat(result.ohlc1m().getOpenPrice()).isEqualByComparingTo("100"); // Updated
        assertThat(result.missingTickLog().getReason()).isEqualTo(ReconciliationReason.MISMATCH);
    }

    @Test
    @DisplayName("Case 3: 데이터가 완벽히 일치하면 null을 반환하여 수정을 건너뛴다")
    void process_MatchedData_ReturnsNull() {
        // given
        long timestamp = 1710000000000L;
        BinanceKline binanceKline = createBinanceKline(timestamp, "100", "110", "90", "105", "1000");

        LocalDateTime bucketTime = ReconciliationBatchConstants.toLocalDateTime(timestamp);
        Ohlc1m existingOhlc = Ohlc1m.builder()
                .symbol(mockSymbol)
                .bucketTime(bucketTime)
                .open(new BigDecimal("100"))
                .high(new BigDecimal("110"))
                .low(new BigDecimal("90"))
                .close(new BigDecimal("105"))
                .volume(VolumeScaler.toLong(new BigDecimal("1000")))
                .build();

        when(symbolService.findBySymbol(symbol)).thenReturn(mockSymbol);
        when(ohlc1mService.findBySymbolIdAndBucketTime(any(), eq(bucketTime))).thenReturn(Optional.of(existingOhlc));

        // when
        ReconciliationResult result = processor.process(binanceKline);

        // then
        assertThat(result).isNull();
    }

    private BinanceKline createBinanceKline(long openTime, String o, String h, String l, String c, String v) {
        return new BinanceKline(
                openTime,
                new BigDecimal(o),
                new BigDecimal(h),
                new BigDecimal(l),
                new BigDecimal(c),
                new BigDecimal(v),
                openTime + 59999,
                BigDecimal.ZERO,
                10,
                BigDecimal.ZERO,
                BigDecimal.ZERO);
    }
}
