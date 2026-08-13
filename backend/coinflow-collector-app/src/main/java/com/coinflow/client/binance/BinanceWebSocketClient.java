package com.coinflow.client.binance;

import com.coinflow.client.DataClient;
import com.coinflow.handler.TickMessageHandler;
import java.net.URI;
import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import lombok.extern.slf4j.Slf4j;
import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;

@Slf4j
public class BinanceWebSocketClient implements DataClient {

    private static final Duration PROACTIVE_RECONNECT_DELAY = Duration.ofHours(23).plusMinutes(50);
    private static final Duration RECONNECT_BASE_DELAY = Duration.ofSeconds(1);
    private static final Duration RECONNECT_MAX_DELAY = Duration.ofMinutes(1);
    private static final String SERVER_SHUTDOWN_EVENT = "\"serverShutdown\"";

    private final URI uri;
    private final TickMessageHandler handler;
    private final Object lifecycleLock = new Object();
    private final ScheduledExecutorService reconnectScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread = new Thread(r, "binance-ws-reconnect");
        thread.setDaemon(true);
        return thread;
    });
    private final AtomicBoolean stopped = new AtomicBoolean(true);
    private final AtomicBoolean reconnectScheduled = new AtomicBoolean(false);
    private final AtomicInteger reconnectAttempts = new AtomicInteger(0);

    private volatile WebSocketClient client;
    private volatile ScheduledFuture<?> reconnectFuture;
    private volatile ScheduledFuture<?> proactiveReconnectFuture;

    public BinanceWebSocketClient(
            String url,
            TickMessageHandler handler
    ) {
        this.uri = URI.create(url);
        this.handler = handler;
    }

    @Override
    public void connect() {
        stopped.set(false);
        cancelReconnect();
        connectNewClient("initial connection");
    }

    @Override
    public void disconnect() {
        stopped.set(true);
        cancelReconnect();
        cancelProactiveReconnect();

        synchronized (lifecycleLock) {
            if (client != null) {
                client.close();
                client = null;
            }
        }

        reconnectScheduler.shutdownNow();
    }

    private WebSocketClient createClient() {
        return new WebSocketClient(uri) {
            @Override
            public void onOpen(ServerHandshake handshake) {
                markConnected(this);
            }

            @Override
            public void onMessage(String message) {
                if (message.contains(SERVER_SHUTDOWN_EVENT)) {
                    log.warn("Binance serverShutdown event received. Scheduling reconnect.");
                    if (isCurrentClient(this)) {
                        scheduleReconnect("serverShutdown event");
                    }
                    return;
                }
                handler.handle(message);
            }

            @Override
            public void onClose(int code, String reason, boolean remote) {
                log.warn(
                        "Binance WebSocket closed. code={}, reason={}, remote={}",
                        code, reason, remote
                );
                handleDisconnected(this, "connection closed");
            }

            @Override
            public void onError(Exception ex) {
                log.error("Binance WebSocket error", ex);
                handleDisconnected(this, "connection error");
            }
        };
    }

    private void connectNewClient(String reason) {
        if (stopped.get()) {
            return;
        }

        synchronized (lifecycleLock) {
            if (stopped.get()) {
                return;
            }
            cancelReconnect();

            WebSocketClient previous = client;
            WebSocketClient next = createClient();
            client = next;

            if (previous != null) {
                previous.close();
            }

            log.info("Connecting Binance WebSocket. reason={}, uri={}", reason, uri);
            next.connect();
        }
    }

    private void scheduleReconnect(String reason) {
        synchronized (lifecycleLock) {
            if (stopped.get() || !reconnectScheduled.compareAndSet(false, true)) {
                return;
            }

            int attempt = reconnectAttempts.incrementAndGet();
            long delayMillis = calculateBackoffMillis(attempt);

            log.warn("Scheduling Binance WebSocket reconnect. reason={}, attempt={}, delayMillis={}",
                    reason, attempt, delayMillis);

            reconnectFuture = reconnectScheduler.schedule(() -> {
                synchronized (lifecycleLock) {
                    reconnectFuture = null;
                    reconnectScheduled.set(false);
                }
                connectNewClient(reason);
            }, delayMillis, TimeUnit.MILLISECONDS);
        }
    }

    private void markConnected(WebSocketClient connectedClient) {
        synchronized (lifecycleLock) {
            if (stopped.get() || client != connectedClient) {
                log.debug("Ignoring stale Binance WebSocket onOpen callback.");
                return;
            }

            cancelReconnect();
            reconnectAttempts.set(0);
            scheduleProactiveReconnect();
            log.info("Binance WebSocket connected. uri={}", uri);
        }
    }

    private void handleDisconnected(WebSocketClient disconnectedClient, String reason) {
        synchronized (lifecycleLock) {
            if (stopped.get() || client != disconnectedClient) {
                log.debug("Ignoring stale Binance WebSocket disconnect callback. reason={}", reason);
                return;
            }

            cancelProactiveReconnect();
        }
        scheduleReconnect(reason);
    }

    private void scheduleProactiveReconnect() {
        cancelProactiveReconnect();
        proactiveReconnectFuture = reconnectScheduler.schedule(
                () -> scheduleReconnect("scheduled 24h renewal"),
                PROACTIVE_RECONNECT_DELAY.toMillis(),
                TimeUnit.MILLISECONDS
        );
        log.info("Scheduled Binance WebSocket proactive reconnect after {} minutes",
                PROACTIVE_RECONNECT_DELAY.toMinutes());
    }

    private void cancelProactiveReconnect() {
        ScheduledFuture<?> future = proactiveReconnectFuture;
        if (future != null) {
            future.cancel(false);
            proactiveReconnectFuture = null;
        }
    }

    private void cancelReconnect() {
        ScheduledFuture<?> future = reconnectFuture;
        if (future != null) {
            future.cancel(false);
            reconnectFuture = null;
        }
        reconnectScheduled.set(false);
    }

    private long calculateBackoffMillis(int attempt) {
        int exponent = Math.min(attempt - 1, 6);
        long exponentialDelay = RECONNECT_BASE_DELAY.toMillis() * (1L << exponent);
        long cappedDelay = Math.min(exponentialDelay, RECONNECT_MAX_DELAY.toMillis());
        long jitter = ThreadLocalRandom.current().nextLong(0, 1_000);
        return cappedDelay + jitter;
    }

    private boolean isCurrentClient(WebSocketClient candidate) {
        return client == candidate;
    }
}
