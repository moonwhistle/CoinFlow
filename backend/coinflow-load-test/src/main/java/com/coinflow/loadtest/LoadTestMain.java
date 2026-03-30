package com.coinflow.loadtest;

import com.coinflow.tick.serialization.TickRawBinaryCodec;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.lettuce.core.XAddArgs;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;
import io.lettuce.core.codec.ByteArrayCodec;
import io.lettuce.core.codec.RedisCodec;
import io.lettuce.core.codec.StringCodec;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

public class LoadTestMain {

    private static final String STREAM_KEY = "tick:raw";
    private static final String PAYLOAD_FIELD = "p";

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
        String redisHost = args.length > 0 ? args[0] : getEnv("REDIS_HOST", "localhost");
        int redisPort = args.length > 1 ? Integer.parseInt(args[1]) : Integer.parseInt(getEnv("REDIS_PORT", "6379"));
        int targetTps = args.length > 2 ? Integer.parseInt(args[2]) : Integer.parseInt(getEnv("TARGET_TPS", "1000"));
        
        List<String> symbols;
        if (args.length > 3) {
            symbols = List.of(java.util.Arrays.copyOfRange(args, 3, args.length));
        } else {
            String envSymbols = getEnv("SYMBOLS", "btcusdt");
            symbols = List.of(envSymbols.split(","));
        }

        System.out.printf("[LoadTest] Config: host=%s port=%d tps=%d symbols=%s%n",
                redisHost, redisPort, targetTps, symbols);

        RedisClient redisClient = RedisClient.create(RedisURI.create(redisHost, redisPort));
        StatefulRedisConnection<String, byte[]> connection = redisClient
                .connect(RedisCodec.of(StringCodec.UTF8, ByteArrayCodec.INSTANCE));
        RedisCommands<String, byte[]> commands = connection.sync();

        int symbolCount = symbols.size();
        BigDecimal[] basePrices = new BigDecimal[symbolCount];
        for (int i = 0; i < symbolCount; i++) {
            basePrices[i] = BASE_PRICE_TABLE[i % BASE_PRICE_TABLE.length];
        }

        AtomicLong totalPublished = new AtomicLong(0);
        AtomicLong lastReported = new AtomicLong(0);
        AtomicLong tickCounter = new AtomicLong(0);
        Random random = new Random();

        long intervalMicros = 1_000_000L / targetTps;
        ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(2);

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
                // 최근 10,000개만 유지 (메모리 폭주 방지)
                commands.xadd(STREAM_KEY, new XAddArgs().maxlen(10000).approximateTrimming(true), fields);
                totalPublished.incrementAndGet();
            } catch (Exception e) {
                System.err.println("[LoadTest] Publish error: " + e.getMessage());
            }
        }, 0, intervalMicros, TimeUnit.MICROSECONDS);

        scheduler.scheduleAtFixedRate(() -> {
            long current = totalPublished.get();
            long previous = lastReported.getAndSet(current);
            long actualTps = (current - previous) / 5;
            System.out.printf("[LoadTest] actualTps=%d/s  totalPublished=%d  symbols=%s%n",
                    actualTps, current, symbols);
        }, 5, 5, TimeUnit.SECONDS);

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            scheduler.shutdownNow();
            connection.close();
            redisClient.shutdown();
        }));

        try {
            Thread.currentThread().join();
        } catch (InterruptedException ignored) {
        }
    }

    private static String getEnv(String key, String defaultValue) {
        String val = System.getenv(key);
        return (val != null && !val.isEmpty()) ? val : defaultValue;
    }
}
