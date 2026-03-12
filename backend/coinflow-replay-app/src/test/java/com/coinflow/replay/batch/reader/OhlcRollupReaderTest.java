package com.coinflow.replay.batch.reader;

import com.coinflow.domain.symbol.domain.Symbol;
import com.coinflow.domain.symbol.service.SymbolService;
import com.coinflow.replay.batch.common.ReconciliationBatchConstants;
import com.coinflow.replay.batch.model.RollupTarget;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OhlcRollupReaderTest {

    @Mock
    private SymbolService symbolService;

    private final String symbolName = "btcusdt";
    private Symbol mockSymbol;

    @BeforeEach
    void setUp() {
        mockSymbol = Symbol.builder()
                .symbol(symbolName)
                .build();
        when(symbolService.findBySymbol(symbolName)).thenReturn(mockSymbol);
    }

    @Test
    @DisplayName("버킷 생성: 주어진 시간 범위 내에서 5분 단위 버킷들을 올바르게 생성한다")
    void read_GeneratesCorrectBuckets() {
        // given
        // 10:00:00 ~ 10:15:00 (총 15분 범위)
        long startMs = toEpochMillis(LocalDateTime.of(2024, 3, 12, 10, 0));
        long endMs = toEpochMillis(LocalDateTime.of(2024, 3, 12, 10, 15)) - 1; // 10:14:59.999

        OhlcRollupReader reader = new OhlcRollupReader(symbolName, ReconciliationBatchConstants.INTERVAL_5M_MINUTES,
                startMs,
                endMs, symbolService);

        // when & then
        // 10:00, 10:05 (10:10은 10:15에 종료되므로 10:14:59 범위에서는 생성되지 않아야 함)
        RollupTarget target1 = reader.read();
        assertThat(target1).isNotNull();
        assertThat(target1.getBucketTime()).isEqualTo(LocalDateTime.of(2024, 3, 12, 10, 0));

        RollupTarget target2 = reader.read();
        assertThat(target2).isNotNull();
        assertThat(target2.getBucketTime()).isEqualTo(LocalDateTime.of(2024, 3, 12, 10, 5));

        assertThat(reader.read()).isNull();
    }

    @Test
    @DisplayName("시간 정렬(Floor): 시작 시간이 정각이 아닐 경우 이전 인터벌 경계로 내림하여 처리한다")
    void read_AlignsStartTimeToFloor() {
        // given
        // 10:03:00 시작 -> 10:00:00으로 정렬되어야 함
        long startMs = toEpochMillis(LocalDateTime.of(2024, 3, 12, 10, 3));
        long endMs = toEpochMillis(LocalDateTime.of(2024, 3, 12, 10, 10));

        OhlcRollupReader reader = new OhlcRollupReader(symbolName, ReconciliationBatchConstants.INTERVAL_5M_MINUTES,
                startMs,
                endMs, symbolService);

        // when & then
        RollupTarget target1 = reader.read();
        assertThat(target1).isNotNull();
        assertThat(target1.getBucketTime()).isEqualTo(LocalDateTime.of(2024, 3, 12, 10, 0));

        RollupTarget target2 = reader.read();
        assertThat(target2).isNotNull();
        assertThat(target2.getBucketTime()).isEqualTo(LocalDateTime.of(2024, 3, 12, 10, 5));

        assertThat(reader.read()).isNull();
    }

    @Test
    @DisplayName("범위 미달: 인터벌보다 짧은 시간 범위가 주어지면 버킷을 생성하지 않는다")
    void read_ReturnsNullWhenRangeTooShort() {
        // given
        // 10:00 ~ 10:04 (5분 미만)
        // 5분봉 인터벌의 경우 10:00 버킷은 10:05:00.000에 닫힘.
        // endMs가 10:05:00.000보다 작으면 해당 버킷은 '닫히지 않은' 것으로 간주하여 생성하지 않음.
        long startMs = toEpochMillis(LocalDateTime.of(2024, 3, 12, 10, 0));
        long endMs = toEpochMillis(LocalDateTime.of(2024, 3, 12, 10, 5)) - 1; // 10:04:59.999

        OhlcRollupReader reader = new OhlcRollupReader(symbolName, ReconciliationBatchConstants.INTERVAL_5M_MINUTES,
                startMs,
                endMs, symbolService);

        // when & then
        assertThat(reader.read()).isNull();
    }

    @Test
    @DisplayName("경계 조건: endMs가 인터벌 종료 시점과 정확히 일치할 때만 버킷을 생성한다")
    void read_BoundaryTest() {
        // given
        long startMs = toEpochMillis(LocalDateTime.of(2024, 3, 12, 10, 0));
        // 정확히 10:05:00.000
        long exactlyEndMs = toEpochMillis(LocalDateTime.of(2024, 3, 12, 10, 5));

        OhlcRollupReader reader = new OhlcRollupReader(symbolName, ReconciliationBatchConstants.INTERVAL_5M_MINUTES,
                startMs,
                exactlyEndMs, symbolService);

        // when & then
        assertThat(reader.read()).isNotNull(); // 10:00 버킷 생성됨 (종료시간 10:05 <= endBoundary 10:05)
        assertThat(reader.read()).isNull();
    }

    private long toEpochMillis(LocalDateTime ldt) {
        return ldt.atZone(ReconciliationBatchConstants.BATCH_ZONE).toInstant().toEpochMilli();
    }
}
