package com.coinflow.aggregation.service;

import com.coinflow.config.ConsumerApplicationShutdown;
import com.coinflow.domain.aggregation.service.KlineAggregatorService;
import com.coinflow.recovery.service.ConsumerCheckpointService;
import com.coinflow.recovery.service.CandleProjectionPublisher;
import java.math.BigDecimal;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.stream.RecordId;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class TickProcessServiceTest {
    private final ConsumerCheckpointService checkpoints = mock(ConsumerCheckpointService.class);
    private final CandleProjectionPublisher projections = mock(CandleProjectionPublisher.class);
    private final BatchAckWorker ack = mock(BatchAckWorker.class);
    private final ConsumerApplicationShutdown shutdown = mock(ConsumerApplicationShutdown.class);
    private final com.coinflow.monitoring.MetricRecorder metrics = mock(com.coinflow.monitoring.MetricRecorder.class);
    private KlineAggregatorService aggregate;
    private TickProcessService ticks;

    @BeforeEach void setup() {
        aggregate = new KlineAggregatorService();
        ticks = new TickProcessService(aggregate, checkpoints, projections, ack, shutdown, metrics);
        when(checkpoints.acquire()).thenReturn(new ConsumerCheckpointService.Restored("0-0", null));
        ticks.restore();
    }

    @Test void acknowledgesOnlyAfterAtomicCheckpointCommit() {
        tick("1-0", 60_000);
        tick("2-0", 120_000);
        verifyNoInteractions(ack, projections);
        ticks.flush();
        var ordered = inOrder(checkpoints, projections, ack);
        ordered.verify(checkpoints).commit(eq("2-0"), any(), argThat(list -> list.size() == 1));
        ordered.verify(projections).publish(any());
        ordered.verify(ack).addAck(RecordId.of("1-0"));
        ordered.verify(ack).addAck(RecordId.of("2-0"));
    }

    @Test void checkpointFailureDoesNotAckOrContinue() {
        tick("1-0", 60_000);
        doThrow(new IllegalStateException("DB down")).when(checkpoints).commit(anyString(), any(), anyList());
        assertThrows(IllegalStateException.class, ticks::flush);
        assertThrows(IllegalStateException.class, () -> tick("2-0", 120_000));
        verifyNoInteractions(ack, projections);
    }

    @Test void committedRecordRedeliveryDoesNotAggregateAgain() {
        tick("1-0", 60_000);
        ticks.flush();
        var before = aggregate.checkpoint();
        tick("1-0", 60_000);
        assertEquals(before, aggregate.checkpoint());
        verify(ack, times(2)).addAck(RecordId.of("1-0"));
    }

    @Test void restartRestoresLiveAndClosedBucketsBeforeReplaying() {
        tick("1-0", 1_799_000);
        tick("2-0", 1_801_000);
        var state = new ConsumerCheckpointService.State(1, aggregate.checkpoint(), Map.of());
        when(checkpoints.acquire()).thenReturn(new ConsumerCheckpointService.Restored("2-0", state));
        var restartedAggregate = new KlineAggregatorService();
        var restarted = new TickProcessService(restartedAggregate, checkpoints, projections, ack, shutdown, metrics);
        restarted.restore();
        tick("3-0", 1_799_500);
        restarted.process("btcusdt", BigDecimal.TEN, BigDecimal.ONE, 1_799_500, "tick", "group", RecordId.of("3-0"));
        assertEquals(aggregate.checkpoint().active(), restartedAggregate.checkpoint().active());
        assertEquals(aggregate.checkpoint().recent(), restartedAggregate.checkpoint().recent());
    }

    @Test void invalidAggregationRollsBackAllIntervals() {
        tick("1-0", 6_000_000);
        var before = aggregate.checkpoint();
        assertThrows(TickProcessService.InvalidTickException.class, () -> tick("2-0", 60_000));
        assertEquals(before, aggregate.checkpoint());
    }

    @Test void cacheFailureDoesNotUndoCommittedCheckpoint() {
        tick("1-0", 60_000);
        doThrow(new IllegalStateException("Redis down")).when(projections).publish(any());
        ticks.flush();
        verify(ack).addAck(RecordId.of("1-0"));
    }

    @Test void trackedFailureAdvancesReplayPositionWithoutAcknowledgingIt() {
        ticks.checkpointSkipped(RecordId.of("1-0"));
        verify(checkpoints).commit(eq("1-0"), any(), eq(java.util.List.of()));
        verifyNoInteractions(ack);
        tick("2-0", 60_000);
        ticks.flush();
        verify(ack).addAck(RecordId.of("2-0"));
        verify(ack, never()).addAck(RecordId.of("1-0"));
    }

    private void tick(String id, long time) {
        ticks.process("btcusdt", BigDecimal.TEN, BigDecimal.ONE, time, "tick", "group", RecordId.of(id));
    }
}
