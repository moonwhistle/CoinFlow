package com.coinflow.recovery.service;

import com.coinflow.aggregation.service.TickProcessService;
import com.coinflow.domain.recovery.domain.ConsumerCheckpoint;
import com.coinflow.domain.recovery.domain.FailedRecord;
import com.coinflow.domain.recovery.repository.FailedRecordRepository;
import com.coinflow.domain.recovery.service.RecoveryLedger;
import com.coinflow.recovery.redis.RedisDeadLetterStore;
import java.util.*;
import java.util.function.Function;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class FailedRecordServiceTest {
    private final RecoveryLedger ledger = mock(RecoveryLedger.class);
    private final FailedRecordRepository repository = mock(FailedRecordRepository.class);
    private final RedisDeadLetterStore dlq = mock(RedisDeadLetterStore.class);
    private final TickProcessService ticks = mock(TickProcessService.class);
    private final Map<String, FailedRecord> records = new HashMap<>();
    private FailedRecordService service;

    @BeforeEach void setup() {
        when(ledger.locked(any())).thenAnswer(i -> i.<Function<ConsumerCheckpoint, Object>>getArgument(0).apply(new ConsumerCheckpoint()));
        when(repository.findById(anyString())).thenAnswer(i -> Optional.ofNullable(records.get(i.getArgument(0))));
        when(repository.save(any())).thenAnswer(i -> {
            FailedRecord record = i.getArgument(0); records.put(record.getId(), record); return record;
        });
        service = new FailedRecordService(ledger, repository, dlq, ticks);
        ReflectionTestUtils.setField(service, "backoffMs", 0L);
    }

    @Test void retriesThreeTimesThenRetainsWaitingRepairState() {
        when(dlq.publishAndAcknowledge(any())).thenThrow(new IllegalStateException("Redis unavailable"));
        service.record("tick", "group", "1-0", new byte[] {1}, null, null, new IllegalArgumentException("invalid"));
        FailedRecord failure = records.values().iterator().next();
        assertEquals(3, failure.getDlqAttempts());
        assertTrue(failure.isDlqExhausted());
        assertEquals(FailedRecord.Status.WAITING_REPAIR, failure.getStatus());
        assertNull(failure.getDlqId());
        assertEquals("", failure.getRequiredCandles());
        service.resume(failure);
        verify(dlq, times(3)).publishAndAcknowledge(any());
    }

    @Test void persistsFailureBeforePublishingAndDoesNotMarkRepaired() {
        when(dlq.publishAndAcknowledge(any())).thenReturn("100-0");
        service.record("tick", "group", "1-0", null, null, null, new IllegalArgumentException("invalid"));
        var ordered = inOrder(ticks, repository, dlq);
        ordered.verify(ticks).flush();
        ordered.verify(repository).save(any());
        ordered.verify(dlq).publishAndAcknowledge(any());
        FailedRecord failure = records.values().iterator().next();
        assertEquals("100-0", failure.getDlqId());
        assertEquals(FailedRecord.Status.WAITING_REPAIR, failure.getStatus());
    }

    @Test void databaseTrackingFailureStopsBeforeRedisAck() {
        doThrow(new IllegalStateException("DB down")).when(repository).save(any());
        assertThrows(IllegalStateException.class, () -> service.record("tick", "group", "1-0", null,
                null, null, new IllegalArgumentException("invalid")));
        verifyNoInteractions(dlq);
    }
}
