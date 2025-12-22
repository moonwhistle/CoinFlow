package com.coinflow.publisher;

import com.coinflow.process.event.Ohlc1mFlushedEvent;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Component
@RequiredArgsConstructor
public class Ohlc1mFlushedEventPublisher {

    private final ApplicationEventPublisher publisher;

    public void publishAfterCommit(Long symbolId, LocalDateTime bucketStart1m) {
        TransactionSynchronizationManager.registerSynchronization(
                new TransactionSynchronization() {
                    @Override
                    public void afterCommit() {
                        publisher.publishEvent(
                                Ohlc1mFlushedEvent.of(symbolId, bucketStart1m)
                        );
                    }
                }
        );
    }
}
