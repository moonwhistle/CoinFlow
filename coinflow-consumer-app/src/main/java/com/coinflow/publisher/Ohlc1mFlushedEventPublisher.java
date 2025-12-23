package com.coinflow.publisher;

import com.coinflow.process.event.Ohlc1mFlushedEvent;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class Ohlc1mFlushedEventPublisher {

    private final ApplicationEventPublisher publisher;

    public void publish(Long symbolId, LocalDateTime bucketStart1m) {
        publisher.publishEvent(Ohlc1mFlushedEvent.of(symbolId, bucketStart1m));
    }
}
