package com.coinflow.config;

import java.util.concurrent.atomic.AtomicBoolean;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class ConsumerApplicationShutdown {

    private final ConfigurableApplicationContext applicationContext;
    private final AtomicBoolean shutdownRequested = new AtomicBoolean(false);

    public void request() {
        if (!shutdownRequested.compareAndSet(false, true)) {
            return;
        }

        log.error("Closing consumer application after a fatal Redis Stream subscription error");
        applicationContext.close();
    }
}
