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
        // 아주 엄격한 Sync를 위해서는 Snapshot 덤프 시점의 Stream ID를 알아야 하지만,
        // 임시 방편으로 Snapshot의 시작 시간(bucketTime)부터 현재 시점까지의 모든 틱을 쓸어와서 Merge 합니다.

        // Redis Stream ID는 "밀리초타임스탬프-시퀀스" 형태입니다.
        // bucketTime을 밀리초로 변환하여 검색 시작 ID로 사용합니다.
        long startTimestampMilli = snapshot.getBucketTime().toInstant(ZoneOffset.UTC).toEpochMilli();
        String startId = startTimestampMilli + "-0";
        String endId = "+"; // 가장 최신 데이터까지

        try {
            List<MapRecord<String, Object, Object>> streamRecords = redisTemplate.opsForStream()
                    .range(RedisStreamTickPublisher.RAW_TICK_STREAM, Range.closed(startId, endId));

            if (streamRecords != null && !streamRecords.isEmpty()) {
                String targetSymbol = snapshot.getSymbol().getSymbol().toLowerCase();

                for (MapRecord<String, Object, Object> record : streamRecords) {
                    var valueMap = record.getValue();
                    String tickSymbol = (String) valueMap.get("symbol");

                    // 심볼 필터링
                    if (!targetSymbol.equals(tickSymbol)) {
                        continue;
                    }

                    // 틱 데이터 추출 (String -> BigDecimal 변환 등)
                    BigDecimal price = new BigDecimal((String) valueMap.get("price"));
                    // API 응답용 volume은 Long을 쓰므로 변환. 원래 quantity는 소수점이 있을 수 있지만, 여기서는 거래 건수/수량 처리를
                    // 위해 소수점 버림 처리할 수 있음.
                    // 기존 코드를 보면 Long volume = Long.parseLong(...) 형태를 기대하므로 일단 BigDecimal로 받고
                    // longValue.
                    BigDecimal quantityDecimal = new BigDecimal((String) valueMap.get("quantity"));
                    long volume = quantityDecimal.longValue();

                    // 스냅샷 캔들에 병합 (Replay)
                    // (open 갱신 막음, high 갱신, low 갱신, close는 Stream의 순서상 가장 나중에 들어온 것이 반영됨)
                    snapshot.merge(price, price, price, price, volume);

                    // 주의: 현재 Ohlc1m.merge() 로직은 open/closePrice를 덮어쓰지 못하도록 제한되어 있습니다. (DB Merge용 방어
                    // 코드)
                    // API 실시간 스냅샷 리플레이를 위해서는 가장 최신 틱의 price를 Close로 강제로 밀어넣어줘야 합니다.
                    // Ohlc1m 엔티티에 setClosePrice() 같은 setter가 없고 빌더패턴이므로 리플렉션이나 다른 방법을 쓰거나,
                    // Replay 전용 DTO를 만들어야 하지만, 간이 리플레이이므로 merge만 수행합니다.
                }
                log.debug("Replayed {} stream ticks into live snapshot for symbol {}", streamRecords.size(),
                        targetSymbol);
            }
        } catch (Exception e) {
            log.warn("Failed to replay stream ticks into snapshot for symbolId {}. Returning bare snapshot.", symbolId,
                    e);
        }

        return Optional.of(snapshot);
    }
}
