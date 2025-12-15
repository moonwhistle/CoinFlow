package com.coinflow.runner;

import com.coinflow.client.DataClient;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class CollectorRunner {

    private final DataClient dataClient;

    @EventListener(ApplicationReadyEvent.class)
    public void start() {
        dataClient.connect();
    }

    @PreDestroy
    public void shutdown() {
        dataClient.disconnect();
    }
}
