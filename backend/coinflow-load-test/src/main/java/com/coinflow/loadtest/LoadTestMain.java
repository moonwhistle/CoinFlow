package com.coinflow.loadtest;

import com.coinflow.tick.serialization.TickRawBinaryCodec;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;
import io.lettuce.core.codec.ByteArrayCodec;
import io.lettuce.core.codec.RedisCodec;
import io.lettuce.core.codec.StringCodec;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Load Test 프로듀서.
 */
public class LoadTestMain {

    private static final String STREAM_KEY = "tick:raw";
    private static final String PAYLOAD_FIELD = "p";

    // 종목별 기준 가격 (symbol 순서에 맞춰 사용, 10개 순환)
    private static final BigDecimal[] BASE_PRICE_TABLE = {
            new BigDecimal("68000.00000000"),
            new BigDecimal("3500.00000000"),
            new BigDecimal("420.00000000"),
            new BigDecimal("180.00000000"),
            new BigDecimal("0.65000000"),
            new BigDecimal("0.45000000"),
            new BigDecimal("82.00000000"),
            new BigDecimal("18.00000000"),
            new BigDecimal("12.00000000"),
            new BigDecimal("10.00000000")
    };

    public static void main(String[] args) {
        // 인자 파싱 (기본값: localhost 6379 1000 btcusdt)
        String redisHost = args.length > 0 ? args[0] : "localhost";
        int redisPort = args.length > 1 ? Integer.parseInt(args[1]) : 6379;
        int targetTps = args.length > 2 ? Integer.parseInt(args[2]) : 1000;
        List<String> symbols = args.length > 3
                ? List.of(java.util.Arrays.copyOfRange(args, 3, args.length))
                : List.of("btcusdt");

        System.out.printf("[LoadTest] Config: host=%s port=%d tps=%d symbols=%s%n",
                redisHost, redisPort, targetTps, symbols);

        // Lettuce Redis 연결 (String key, byte[] value 혼합 코덱)
        RedisClient redisClient = RedisClient.create(RedisURI.create(redisHost, redisPort));
        StatefulRedisConnection<String, byte[]> connection = redisClient
                .connect(RedisCodec.of(StringCodec.UTF8, ByteArrayCodec.INSTANCE));
        RedisCommands<String, byte[]> commands = connection.sync();

        // 스트림 초기화 (MAXLEN ~ 10000 유지)
        System.out.println("[LoadTest] Connected to Redis. Starting tick injection...");

        // 종목별 기준 가격 배열 준비
        int symbolCount = symbols.size();
        BigDecimal[] basePrices = new BigDecimal[symbolCount];
        for (int i = 0; i < symbolCount; i++) {
            basePrices[i] = BASE_PRICE_TABLE[i % BASE_PRICE_TABLE.length];
        }

        AtomicLong totalPublished = new AtomicLong(0);
        AtomicLong lastReported = new AtomicLong(0);
        AtomicLong tickCounter = new AtomicLong(0);
        Random random = new Random();

        // 목표 TPS → 마이크로초 단위 인터벌
        long intervalMicros = 1_000_000L / targetTps;

        ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(2);

        // Tick 발행 태스크
        scheduler.scheduleAtFixedRate(() -> {
            int idx = (int) (tickCounter.getAndIncrement() % symbolCount);
            String symbol = symbols.get(idx);
            BigDecimal base = basePrices[idx];

            double fluctuation = 1.0 + (random.nextDouble() - 0.5) * 0.01;
            BigDecimal price = base.multiply(BigDecimal.valueOf(fluctuation))
                    .setScale(8, RoundingMode.HALF_UP);
            BigDecimal qty = BigDecimal.valueOf(0.001 + random.nextDouble() * 0.099)
                    .setScale(8, RoundingMode.HALF_UP);
            long eventTime = System.currentTimeMillis();

            try {
                byte[] payload = TickRawBinaryCodec.encode(symbol, price, qty, eventTime);
                Map<String, byte[]> fields = new HashMap<>();
                fields.put(PAYLOAD_FIELD, payload);
                commands.xadd(STREAM_KEY, fields);
                totalPublished.incrementAndGet();
            } catch (Exception e) {
                System.err.println("[LoadTest] Publish error: " + e.getMessage());
            }
        }, 0, intervalMicros, TimeUnit.MICROSECONDS);

        // 5초마다 실제 TPS 리포트
        scheduler.scheduleAtFixedRate(() -> {
            long current = totalPublished.get();
            long previous = lastReported.getAndSet(current);
            long actualTps = (current - previous) / 5;
            System.out.printf("[LoadTest] actualTps=%d/s  totalPublished=%d  symbols=%s%n",
                    actualTps, current, symbols);
        }, 5, 5, TimeUnit.SECONDS);

        // JVM 종료 훅
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            scheduler.shutdownNow();
            connection.close();
            redisClient.shutdown();
            System.out.printf("[LoadTest] Stopped. totalPublished=%d%n", totalPublished.get());
        }));

        // 메인 스레드 유지
        try {
            Thread.currentThread().join();
        } catch (InterruptedException ignored) {
        }
    }
}
