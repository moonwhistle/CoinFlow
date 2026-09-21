package com.coinflow.recovery.redis;

import com.coinflow.domain.recovery.repository.RecoveryStreamRepository;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class RedisRecoveryStreamRepository implements RecoveryStreamRepository {
    private final RedisTemplate<String, String> redisTemplate;
    private static final DefaultRedisScript<Long> TRIM = new DefaultRedisScript<>(
            "return redis.call('XTRIM', KEYS[1], 'MINID', ARGV[1])", Long.class);
    private static final DefaultRedisScript<Long> CLEAR_LIVE = new DefaultRedisScript<>("""
            local value = redis.call('GET', KEYS[1])
            if value and tonumber(cjson.decode(value).startTime) == tonumber(ARGV[1]) then
              return redis.call('DEL', KEYS[1])
            end
            return 0
            """, Long.class);

    @Override
    public List<String> pending(String stream, String group, String after, String through, int count) {
        Range<String> range = Range.of(Range.Bound.exclusive(after),
                through == null ? Range.Bound.unbounded() : Range.Bound.inclusive(through));
        var messages = redisTemplate.opsForStream().pending(stream, group, range, count);
        List<String> ids = new ArrayList<>();
        messages.forEach(message -> ids.add(message.getIdAsString()));
        return ids;
    }

    @Override
    public void acknowledge(String stream, String group, List<String> ids) {
        if (ids.isEmpty()) return;
        if (redisTemplate.opsForStream().acknowledge(stream, group, ids.toArray(String[]::new)) == null) {
            throw new IllegalStateException("Missing XACK confirmation");
        }
    }

    @Override
    public boolean hasSingleGroup(String stream) { return redisTemplate.opsForStream().groups(stream).size() == 1; }

    @Override
    public void trimBefore(String stream, String floor) {
        redisTemplate.execute(TRIM, List.of(stream), floor);
    }

    @Override
    public void clearLive(String symbol, String interval, long bucket) {
        redisTemplate.execute(CLEAR_LIVE, List.of("kline:live:" + symbol.toLowerCase(Locale.ROOT) + ":" + interval), Long.toString(bucket));
    }
}
