package com.coinflow.api.provider;

import com.coinflow.domain.ohlc.constant.OhlcInterval;
import com.coinflow.domain.ohlc.domain.Ohlc1m;
import com.coinflow.domain.ohlc.provider.RealTimeOhlcProvider;
import com.coinflow.domain.ohlc.repository.OhlcLiveSnapshotRepository;
import com.coinflow.domain.ohlc.snapshot.LiveCandleSnapshot;
import com.coinflow.publish.stream.RedisStreamTickPublisher;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
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

    @Override
    public Optional<Ohlc1m> getRealTimeCandle(Long symbolId, LocalDateTime bucketTime) {
        log.debug("OhlcChartSyncProvider: getRealTimeCandle called for symbolId={}, bucketTime={}", symbolId,
                bucketTime);

        // 1. Redis에서 LiveCandleSnapshot 조회
        Optional<LiveCandleSnapshot> snapshotOpt = snapshotRepository.find(symbolId, OhlcInterval.M1);
        if (snapshotOpt.isEmpty()) {
            log.debug("OhlcChartSyncProvider: Snapshot NOT found in Redis for symbolId={}", symbolId);
            return Optional.empty();
        }

        LiveCandleSnapshot snapshot = snapshotOpt.get();
        log.debug("OhlcChartSyncProvider: Snapshot found with bucketTime={} (requested={}), lastStreamId={}",
                snapshot.bucketTime(), bucketTime, snapshot.lastStreamId());

        // 요청된 bucketTime과 다르면 무시 (과거 데이터 스냅샷 방지)
        if (!snapshot.bucketTime().equals(bucketTime)) {
            log.debug("OhlcChartSyncProvider: Bucket times do NOT match. Ignoring snapshot.");
            return Optional.empty();
        }

        // 2. lastStreamId 이후의 누락 틱만 Stream에서 조회 (정밀 Replay)
        BigDecimal replayedHigh = snapshot.high();
        BigDecimal replayedLow = snapshot.low();
        BigDecimal replayedClose = snapshot.close();
        long replayedVolume = snapshot.volume();

        String lastStreamId = snapshot.lastStreamId();

        if (lastStreamId != null) {
            try {
                // "(" prefix로 exclusive start (lastStreamId 자체는 이미 처리된 것이므로 제외)
                String startId = "(" + lastStreamId;
                String endId = "+";

                List<MapRecord<String, Object, Object>> streamRecords = redisTemplate.opsForStream()
                        .range(RedisStreamTickPublisher.RAW_TICK_STREAM, Range.closed(startId, endId));

                if (streamRecords != null && !streamRecords.isEmpty()) {
                    String targetSymbol = snapshot.symbolCode().toLowerCase();
                    int replayedCount = 0;

                    for (MapRecord<String, Object, Object> record : streamRecords) {
                        var valueMap = record.getValue();
                        String tickSymbol = (String) valueMap.get("symbol");

                        if (!targetSymbol.equals(tickSymbol)) {
                            continue;
                        }

                        BigDecimal price = new BigDecimal((String) valueMap.get("price"));
                        BigDecimal quantityDecimal = new BigDecimal((String) valueMap.get("quantity"));
                        long volume = quantityDecimal.setScale(0, RoundingMode.DOWN).longValue();

                        replayedHigh = replayedHigh.max(price);
                        replayedLow = replayedLow.min(price);
                        replayedClose = price;
                        replayedVolume += volume;
                        replayedCount++;
                    }
                    log.debug("Replayed {} stream ticks (after streamId={}) for symbol {}",
                            replayedCount, lastStreamId, targetSymbol);
                }
            } catch (Exception e) {
                log.warn("Failed to replay stream ticks for symbolId {}. Returning bare snapshot.", symbolId, e);
            }
        }

        // 3. Ohlc1m 엔티티로 변환하여 반환 (Ohlc1mService 호환)
        Ohlc1m result = Ohlc1m.builder()
                .symbol(null) // Symbol은 API 응답(OhlcCandleSnapshot.from)에서 사용되지 않음
                .bucketTime(snapshot.bucketTime())
                .open(snapshot.open())
                .high(replayedHigh)
                .low(replayedLow)
                .close(replayedClose)
                .volume(replayedVolume)
                .build();

        return Optional.of(result);
    }
}
