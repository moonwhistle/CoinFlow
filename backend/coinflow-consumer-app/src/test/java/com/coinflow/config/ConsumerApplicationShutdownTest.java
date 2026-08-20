package com.coinflow.config;

import org.junit.jupiter.api.Test;
import org.springframework.context.ConfigurableApplicationContext;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

class ConsumerApplicationShutdownTest {

    @Test
    void closesApplicationContextOnlyOnce() {
        ConfigurableApplicationContext applicationContext = mock(ConfigurableApplicationContext.class);
        ConsumerApplicationShutdown shutdown = new ConsumerApplicationShutdown(applicationContext);

        shutdown.request();
        shutdown.request();

        verify(applicationContext, times(1)).close();
    }
}
