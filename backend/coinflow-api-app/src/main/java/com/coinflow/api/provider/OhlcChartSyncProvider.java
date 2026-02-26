package com.coinflow.api.provider;

import com.coinflow.domain.ohlc.constant.OhlcInterval;
import com.coinflow.domain.ohlc.domain.Ohlc1m;
import com.coinflow.domain.ohlc.provider.RealTimeOhlcProvider;
import com.coinflow.domain.ohlc.repository.OhlcLiveSnapshotRepository;
import com.coinflow.publish.stream.RedisStreamTickPublisher;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

@Slf4j
@Component
@RequiredArgsConstructor
public class OhlcChartSyncProvider implements RealTimeOhlcProvider {

    private final OhlcLiveSnapshotRepository snapshotRepository;
    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    @Override
    public Optional<Ohlc1m> getRealTimeCandle(Long symbolId, LocalDateTime bucketTime) {
        log.debug("OhlcChartSyncProvider: getRealTimeCandle called for symbolId={}, bucketTime={}", symbolId,
                bucketTime);
        // 1. Snapshot 조회 (consumer-app이 1초마다 덮어쓰는 값)
        Optional<Ohlc1m> snapshotOpt = snapshotRepository.find(symbolId, OhlcInterval.M1);
        if (snapshotOpt.isEmpty()) {
            log.debug("OhlcChartSyncProvider: Snapshot NOT found in Redis for symbolId={}", symbolId);
            return Optional.empty();
        }

        Ohlc1m snapshot = snapshotOpt.get();
        log.debug("OhlcChartSyncProvider: Snapshot found with bucketTime={} (requested={})", snapshot.getBucketTime(),
                bucketTime);

        // 요청된 bucketTime 과 다르면 무시 (과거 데이터 스냅샷 방지)
        if (!snapshot.getBucketTime().equals(bucketTime)) {
            log.debug("OhlcChartSyncProvider: Bucket times do NOT match. Ignoring snapshot.");
            return Optional.empty();
        }

        // 2. Snapshot의 bucketTime부터 현재시간까지의 누락 틱을 Stream에서 조회 후 병합 (Replay)
        long startTimestampMilli = snapshot.getBucketTime().toInstant(ZoneOffset.UTC).toEpochMilli();
        String startId = startTimestampMilli + "-0";
        String endId = "+"; // 가장 최신 데이터까지

        Ohlc1m replayedCandle = snapshot;

        try {
            List<MapRecord<String, Object, Object>> streamRecords = redisTemplate.opsForStream()
                    .range(RedisStreamTickPublisher.RAW_TICK_STREAM, Range.closed(startId, endId));

            if (streamRecords != null && !streamRecords.isEmpty()) {
                String targetSymbol = snapshot.getSymbol().getSymbol().toLowerCase();
                int replayedCount = 0;

                for (MapRecord<String, Object, Object> record : streamRecords) {
                    var valueMap = record.getValue();
                    String tickSymbol = (String) valueMap.get("symbol");

                    if (!targetSymbol.equals(tickSymbol)) {
                        continue;
                    }

                    BigDecimal price = new BigDecimal((String) valueMap.get("price"));
                    BigDecimal quantityDecimal = new BigDecimal((String) valueMap.get("quantity"));
                    long volume = quantityDecimal.setScale(0, java.math.RoundingMode.DOWN).longValue() == 0 ? 1L
                            : quantityDecimal.longValue();

                    replayedCandle = Ohlc1m.builder()
                            .symbol(replayedCandle.getSymbol())
                            .bucketTime(replayedCandle.getBucketTime())
                            .open(replayedCandle.getOpenPrice())
                            .high(replayedCandle.getHighPrice().max(price))
                            .low(replayedCandle.getLowPrice().min(price))
                            .close(price)
                            .volume(replayedCandle.getVolume() + volume)
                            .build();
                    replayedCount++;
                }
                log.debug("Replayed {} stream ticks into live snapshot for symbol {}", replayedCount, targetSymbol);
            }
        } catch (Exception e) {
            log.warn("Failed to replay stream ticks into snapshot for symbolId {}. Returning bare snapshot.", symbolId,
                    e);
        }

        return Optional.of(replayedCandle);
    }
}
