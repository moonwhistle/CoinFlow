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
        // 1. Snapshot 조회 (consumer-app이 1초마다 덮어쓰는 값)
        Optional<Ohlc1m> snapshotOpt = snapshotRepository.find(symbolId, OhlcInterval.M1);
        if (snapshotOpt.isEmpty()) {
            return Optional.empty();
        }

        Ohlc1m snapshot = snapshotOpt.get();

        // 요청된 bucketTime 과 다르면 무시 (과거 데이터 스냅샷 방지)
        if (!snapshot.getBucketTime().equals(bucketTime)) {
            return Optional.empty();
        }

        // 2. Snapshot의 updatedAt(서버기준)부터 현재시간까지의 누락 틱을 Stream에서 조회 후 병합 (Replay)
        // 현재 Ohlc1m 엔티티에 updatedAt 대신 tick last time을 알 수 있는 방법이 없으므로,
        // 간단히 "현재시간을 기준으로 가져오되, Snapshot은 사실상 1초 전 데이터이므로 큰 데이터 유실은 없음"
        // 완벽한 병합을 위해서는 Snapshot 저장 시점의 Stream ID(또는 타임스탬프)를 알아야 함.
        // 현재는 Step 2의 기초로 Snapshot 반환만 테스트하고 스트림 병합은 고도화 파트로 넘김.

        return Optional.of(snapshot);
    }
}
