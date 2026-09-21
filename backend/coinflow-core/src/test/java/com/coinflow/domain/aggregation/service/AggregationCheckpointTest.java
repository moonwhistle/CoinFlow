package com.coinflow.domain.aggregation.service;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class AggregationCheckpointTest {
    @Test
    void restoresAllIntervalsAndLateBuffersWithoutDoubleCounting() {
        var uninterrupted = new KlineAggregatorService();
        tick(uninterrupted, 1_799_000);
        tick(uninterrupted, 1_801_000);
        var checkpoint = uninterrupted.checkpoint();
        var restarted = new KlineAggregatorService();
        restarted.restore(checkpoint);
        assertEquals(checkpoint, restarted.checkpoint());
        for (long time : List.of(1_799_500L, 1_802_000L, 3_601_000L)) {
            assertEquals(tick(uninterrupted, time), tick(restarted, time));
        }
        assertEquals(uninterrupted.checkpoint().active(), restarted.checkpoint().active());
    }

    @Test
    void restoringDoesNotExtendLateBufferExpiry() {
        var service = new KlineAggregatorService();
        tick(service, 60_000);
        tick(service, 120_000);
        var state = service.checkpoint();
        var expired = new KlineAggregatorService.Buffered(state.recent().get("btcusdt:M1").get(0).snapshot(), 0);
        service.restore(new KlineAggregatorService.Checkpoint(state.active(), Map.of("btcusdt:M1", List.of(expired))));
        assertTrue(tick(service, 61_000).lateUpdatedSnapshots().isEmpty());
    }

    private com.coinflow.domain.aggregation.domain.vo.AggregationResult tick(KlineAggregatorService service, long time) {
        return service.processTickAndGetResult("btcusdt", BigDecimal.TEN, BigDecimal.ONE, time);
    }
}
