package com.coinflow.aggregation.service.flush;

import com.coinflow.aggregation.event.Ohlc1mFlushedEvent;
import java.time.Clock;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class Ohlc1mFlushedEventPublisher {

    private final ApplicationEventPublisher publisher;
    private final Clock clock;

    public void publish(Long symbolId, LocalDateTime bucketStart1m) {
        publisher.publishEvent(new Ohlc1mFlushedEvent(
                symbolId,
                bucketStart1m,
                clock.instant()
        ));
    }
}
