package com.coinflow.loadtest;

import com.coinflow.common.config.RedisConfig;
import com.coinflow.monitoring.MetricRecorder;
import com.coinflow.publish.DeliveryStatusProvider;
import com.coinflow.publish.config.CollectorDeliveryProperties;
import com.coinflow.publish.stream.QueuedPipelinedTickPublisher;
import com.coinflow.publish.stream.RedisStreamTickPublisher;
import com.coinflow.publish.wal.WalPipelinedTickPublisher;
import com.coinflow.tick.publisher.TickPublisher;
import com.coinflow.tick.serialization.TickRawBinaryCodec;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.distribution.ValueAtPercentile;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;
import org.HdrHistogram.Histogram;
import org.HdrHistogram.Recorder;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.RedisTemplate;

/** Isolated DIRECT / PIPELINE / WAL_PIPELINE comparison using production publishers. */
public final class CollectorDeliveryBenchmarkMain {

    private CollectorDeliveryBenchmarkMain() {}

    public static void main(String[] args) throws Exception {
        Config config = Config.parse(args);
        String streamKey = "tick:benchmark:" + config.mode().name().toLowerCase() + ':' + System.nanoTime();
        Path walDirectory = Files.createTempDirectory("coinflow-wal-benchmark-");

        RedisStandaloneConfiguration redisConfiguration = new RedisStandaloneConfiguration(config.host(), config.port());
        if (!config.password().isBlank()) {
            redisConfiguration.setPassword(config.password());
        }
        LettuceConnectionFactory connectionFactory = new LettuceConnectionFactory(redisConfiguration);
        connectionFactory.afterPropertiesSet();
        connectionFactory.start();

        RedisTemplate<String, byte[]> template = new RedisConfig().rawRedisTemplate(connectionFactory);
        template.afterPropertiesSet();
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        MetricRecorder metrics = new MetricRecorder(registry);
        ConfirmationTracker confirmationTracker = new ConfirmationTracker();
        RedisStreamTickPublisher direct = new RedisStreamTickPublisher(
                template, metrics, streamKey, 200_000L, confirmationTracker::confirmed);

        CollectorDeliveryProperties properties = new CollectorDeliveryProperties();
        properties.setMode(config.mode());
        properties.setBatchSize(500);
        properties.setFlushInterval(Duration.ofMillis(10));
        properties.setQueueCapacity(100_000);
        properties.setRetryInitialDelay(Duration.ofMillis(100));
        properties.setRetryMaxDelay(Duration.ofSeconds(5));
        properties.setWalDirectory(walDirectory);
        properties.setWalSegmentBytes(128L * 1024 * 1024);
        properties.setWalMaxBytes(1024L * 1024 * 1024);

        TickPublisher publisher = switch (config.mode()) {
            case DIRECT -> direct;
            case PIPELINE -> new QueuedPipelinedTickPublisher(direct, metrics, properties);
            case WAL_PIPELINE -> new WalPipelinedTickPublisher(direct, metrics, properties);
        };
        if (publisher instanceof InitializingBean initializingBean) {
            initializingBean.afterPropertiesSet();
        }

        try {
            runPhase(publisher, config.targetTps(), config.warmupSeconds(), 0L, null, null);
            awaitDrain(publisher, Duration.ofMinutes(2));
            confirmationTracker.resetIntervals();

            long acceptedBefore = count(publisher, "accepted");
            long confirmedBefore = count(publisher, "confirmed");
            long walAppendBytesBefore = count(publisher, "walAppendBytes");
            long cpuBefore = processCpuTime();
            long gcCountBefore = gcCount();
            long gcTimeBefore = gcTime();
            RedisUsage redisBefore = RedisUsage.capture(template);
            long started = System.nanoTime();
            Histogram publishLatencyMicros = new Histogram(TimeUnit.MINUTES.toMicros(1), 3);
            long submitted = runPhase(publisher, config.targetTps(), config.durationSeconds(), 10_000_000L,
                    publishLatencyMicros, confirmationTracker);
            long submitFinished = System.nanoTime();
            awaitDrain(publisher, Duration.ofMinutes(5));
            long drained = System.nanoTime();
            RedisUsage redisAfter = RedisUsage.capture(template);
            Histogram endToEndLatencyMicros = confirmationTracker.endToEndInterval();
            Histogram pipelineRttMicros = confirmationTracker.pipelineRttInterval();
            Histogram batchSizes = confirmationTracker.batchSizeInterval();

            long accepted = config.mode() == CollectorDeliveryProperties.Mode.DIRECT
                    ? submitted : count(publisher, "accepted") - acceptedBefore;
            long confirmed = config.mode() == CollectorDeliveryProperties.Mode.DIRECT
                    ? submitted : count(publisher, "confirmed") - confirmedBefore;
            long walAppendBytes = count(publisher, "walAppendBytes") - walAppendBytesBefore;
            double submitSeconds = (submitFinished - started) / 1_000_000_000.0;
            double totalSeconds = (drained - started) / 1_000_000_000.0;
            double cpuPercentOneCore = (processCpuTime() - cpuBefore) / 1_000_000_000.0 / totalSeconds * 100.0;
            double redisCpuPercentOneCore = redisAfter.cpuSecondsDelta(redisBefore) / totalSeconds * 100.0;
            long redisNetInputBytes = redisAfter.netInputBytes() - redisBefore.netInputBytes();
            long redisNetOutputBytes = redisAfter.netOutputBytes() - redisBefore.netOutputBytes();
            Timer walAppendTimer = registry.find("collector.wal.append.latency").timer();

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("timestamp", Instant.now().toString());
            result.put("mode", config.mode());
            result.put("targetTps", config.targetTps());
            result.put("warmupSeconds", config.warmupSeconds());
            result.put("durationSeconds", config.durationSeconds());
            result.put("submitted", submitted);
            result.put("accepted", accepted);
            result.put("confirmed", confirmed);
            result.put("submitTps", submitted / submitSeconds);
            result.put("confirmedTpsIncludingDrain", confirmed / totalSeconds);
            result.put("drainMillis", (drained - submitFinished) / 1_000_000.0);
            result.put("publishCallP50Micros", publishLatencyMicros.getValueAtPercentile(50));
            result.put("publishCallP95Micros", publishLatencyMicros.getValueAtPercentile(95));
            result.put("publishCallP99Micros", publishLatencyMicros.getValueAtPercentile(99));
            result.put("publishCallMaxMicros", publishLatencyMicros.getMaxValue());
            result.put("endToEndP50Micros", endToEndLatencyMicros.getValueAtPercentile(50));
            result.put("endToEndP95Micros", endToEndLatencyMicros.getValueAtPercentile(95));
            result.put("endToEndP99Micros", endToEndLatencyMicros.getValueAtPercentile(99));
            result.put("endToEndMaxMicros", endToEndLatencyMicros.getMaxValue());
            result.put("pipelineCalls", batchSizes.getTotalCount());
            result.put("averageBatchSize", batchSizes.getMean());
            result.put("pipelineRttP50Micros", pipelineRttMicros.getValueAtPercentile(50));
            result.put("pipelineRttP95Micros", pipelineRttMicros.getValueAtPercentile(95));
            result.put("pipelineRttP99Micros", pipelineRttMicros.getValueAtPercentile(99));
            result.put("processCpuPctOfOneCore", cpuPercentOneCore);
            result.put("redisCpuPctOfOneCore", redisCpuPercentOneCore);
            result.put("redisNetInputBytesPerSec", redisNetInputBytes / totalSeconds);
            result.put("redisNetOutputBytesPerSec", redisNetOutputBytes / totalSeconds);
            result.put("heapUsedMiB", ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed() / 1024.0 / 1024.0);
            result.put("gcCount", gcCount() - gcCountBefore);
            result.put("gcTimeMillis", gcTime() - gcTimeBefore);
            result.put("walAppendBytes", walAppendBytes);
            result.put("walBytesPerAccepted", accepted == 0 ? 0 : (double) walAppendBytes / accepted);
            result.put("walAppendP50Micros", timerPercentileMicros(walAppendTimer, 0.50));
            result.put("walAppendP95Micros", timerPercentileMicros(walAppendTimer, 0.95));
            result.put("walAppendP99Micros", timerPercentileMicros(walAppendTimer, 0.99));
            result.put("walFileBytes", count(publisher, "walBytes"));
            result.put("streamLength", template.opsForStream().size(streamKey));
            result.put("publisherDetails", publisher instanceof DeliveryStatusProvider status ? status.details() : Map.of());

            String json = new ObjectMapper().writeValueAsString(result);
            System.out.println("COLLECTOR_DELIVERY_BENCHMARK_RESULT " + json);
            if (config.output() != null) {
                Files.createDirectories(config.output().toAbsolutePath().getParent());
                Files.writeString(config.output(), json + System.lineSeparator());
            }
        } finally {
            if (publisher instanceof DisposableBean disposableBean) {
                disposableBean.destroy();
            }
            template.delete(streamKey);
            connectionFactory.destroy();
            registry.close();
        }
    }

    private static long runPhase(
            TickPublisher publisher,
            long targetTps,
            int seconds,
            long tradeIdBase,
            Histogram latency,
            ConfirmationTracker confirmationTracker
    ) {
        long started = System.nanoTime();
        long deadline = started + TimeUnit.SECONDS.toNanos(seconds);
        long interval = targetTps <= 0 ? 0 : 1_000_000_000L / targetTps;
        long next = started;
        long submitted = 0;
        BigDecimal price = new BigDecimal("65000.12345678");
        BigDecimal quantity = new BigDecimal("0.00123456");
        while (System.nanoTime() < deadline) {
            if (targetTps <= 0 && (submitted & 255) == 0) {
                applySustainedBackpressure(publisher, 100_000);
            }
            if (interval > 0) {
                long remaining = next - System.nanoTime();
                if (remaining > 0) {
                    LockSupport.parkNanos(remaining);
                }
            }
            long before = System.nanoTime();
            byte[] payload = TickRawBinaryCodec.encode(
                    "btcusdt", tradeIdBase + submitted, price, quantity, System.currentTimeMillis());
            long tradeId = tradeIdBase + submitted;
            if (confirmationTracker != null) {
                confirmationTracker.submitted(tradeId, before);
            }
            try {
                publisher.publish(payload);
            } catch (RuntimeException e) {
                if (confirmationTracker != null) {
                    confirmationTracker.failed(tradeId);
                }
                throw e;
            }
            if (latency != null) {
                latency.recordValue(Math.max(1, TimeUnit.NANOSECONDS.toMicros(System.nanoTime() - before)));
            }
            submitted++;
            next = started + submitted * interval;
        }
        return submitted;
    }

    private static void applySustainedBackpressure(TickPublisher publisher, long maxOutstanding) {
        if (!(publisher instanceof DeliveryStatusProvider status)) {
            return;
        }
        while (true) {
            Map<String, Object> details = status.details();
            long outstanding = number(details.get("accepted")) - number(details.get("confirmed"));
            if (outstanding < maxOutstanding) {
                return;
            }
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(1));
        }
    }

    private static final class ConfirmationTracker {
        private final ConcurrentHashMap<Long, Long> submittedAtNanos = new ConcurrentHashMap<>();
        private final Recorder endToEndMicros = new Recorder(TimeUnit.MINUTES.toMicros(5), 3);
        private final Recorder pipelineRttMicros = new Recorder(TimeUnit.MINUTES.toMicros(1), 3);
        private final Recorder batchSizes = new Recorder(1_000_000, 3);

        void submitted(long tradeId, long startedNanos) {
            submittedAtNanos.put(tradeId, startedNanos);
        }

        void failed(long tradeId) {
            submittedAtNanos.remove(tradeId);
        }

        void confirmed(List<byte[]> payloads, long roundTripNanos) {
            long confirmedAt = System.nanoTime();
            batchSizes.recordValue(payloads.size());
            pipelineRttMicros.recordValue(Math.max(1, TimeUnit.NANOSECONDS.toMicros(roundTripNanos)));
            for (byte[] payload : payloads) {
                long tradeId = TickRawBinaryCodec.extractTradeId(payload);
                Long started = submittedAtNanos.remove(tradeId);
                if (started != null) {
                    endToEndMicros.recordValue(Math.max(1,
                            TimeUnit.NANOSECONDS.toMicros(confirmedAt - started)));
                }
            }
        }

        Histogram endToEndInterval() {
            if (!submittedAtNanos.isEmpty()) {
                throw new IllegalStateException("Confirmed callback missing for " + submittedAtNanos.size() + " ticks");
            }
            return endToEndMicros.getIntervalHistogram();
        }

        Histogram pipelineRttInterval() {
            return pipelineRttMicros.getIntervalHistogram();
        }

        Histogram batchSizeInterval() {
            return batchSizes.getIntervalHistogram();
        }

        void resetIntervals() {
            endToEndMicros.getIntervalHistogram();
            pipelineRttMicros.getIntervalHistogram();
            batchSizes.getIntervalHistogram();
        }
    }

    private static void awaitDrain(TickPublisher publisher, Duration timeout) throws InterruptedException {
        if (!(publisher instanceof DeliveryStatusProvider status)) {
            return;
        }
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            Map<String, Object> details = status.details();
            long accepted = number(details.get("accepted"));
            long confirmed = number(details.get("confirmed"));
            if (confirmed >= accepted) {
                return;
            }
            Thread.sleep(10);
        }
        throw new IllegalStateException("Publisher backlog did not drain: " + status.details());
    }

    private static long count(TickPublisher publisher, String key) {
        if (!(publisher instanceof DeliveryStatusProvider status)) {
            return 0;
        }
        return number(status.details().get(key));
    }

    private static long number(Object value) {
        return value instanceof Number number ? number.longValue() : 0L;
    }

    private static long processCpuTime() {
        return ((com.sun.management.OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean())
                .getProcessCpuTime();
    }

    private static long gcCount() {
        return ManagementFactory.getGarbageCollectorMXBeans().stream()
                .mapToLong(GarbageCollectorMXBean::getCollectionCount).filter(value -> value >= 0).sum();
    }

    private static long gcTime() {
        return ManagementFactory.getGarbageCollectorMXBeans().stream()
                .mapToLong(GarbageCollectorMXBean::getCollectionTime).filter(value -> value >= 0).sum();
    }

    private static double timerPercentileMicros(Timer timer, double percentile) {
        if (timer == null) {
            return 0;
        }
        for (ValueAtPercentile value : timer.takeSnapshot().percentileValues()) {
            if (Math.abs(value.percentile() - percentile) < 0.0001) {
                return value.value(TimeUnit.MICROSECONDS);
            }
        }
        return 0;
    }

    private record RedisUsage(double cpuSeconds, long netInputBytes, long netOutputBytes) {
        static RedisUsage capture(RedisTemplate<String, byte[]> template) {
            Properties cpu = template.execute((RedisCallback<Properties>) connection ->
                    connection.serverCommands().info("cpu"));
            Properties stats = template.execute((RedisCallback<Properties>) connection ->
                    connection.serverCommands().info("stats"));
            return new RedisUsage(
                    decimal(cpu, "used_cpu_sys") + decimal(cpu, "used_cpu_user"),
                    integer(stats, "total_net_input_bytes"),
                    integer(stats, "total_net_output_bytes"));
        }

        double cpuSecondsDelta(RedisUsage before) {
            return cpuSeconds - before.cpuSeconds;
        }

        private static double decimal(Properties properties, String name) {
            return Double.parseDouble(properties.getProperty(name, "0"));
        }

        private static long integer(Properties properties, String name) {
            return Long.parseLong(properties.getProperty(name, "0"));
        }
    }

    private record Config(
            String host,
            int port,
            CollectorDeliveryProperties.Mode mode,
            long targetTps,
            int warmupSeconds,
            int durationSeconds,
            String password,
            Path output
    ) {
        static Config parse(String[] args) {
            if (args.length < 6) {
                throw new IllegalArgumentException(
                        "args: <host> <port> <DIRECT|PIPELINE|WAL_PIPELINE> <targetTps; 0=max> <warmupSec> <durationSec> [password] [output]");
            }
            return new Config(
                    args[0], Integer.parseInt(args[1]), CollectorDeliveryProperties.Mode.valueOf(args[2]),
                    Long.parseLong(args[3]), Integer.parseInt(args[4]), Integer.parseInt(args[5]),
                    args.length > 6 && !"-".equals(args[6]) ? args[6] : "",
                    args.length > 7 ? Path.of(args[7]) : null);
        }
    }
}
