package com.coinflow.recovery.redis;

import com.coinflow.domain.recovery.domain.FailedRecord;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Repository;

/** Standalone Redis (all keys must share a hash slot if cluster support is added). */
@Repository
@RequiredArgsConstructor
public class RedisDeadLetterStore {
    private final RedisTemplate<String, String> redisTemplate;
    static final DefaultRedisScript<String> PUBLISH_AND_ACK = new DefaultRedisScript<>("""
            local streamType = redis.call('TYPE', KEYS[2]).ok
            local indexType = redis.call('TYPE', KEYS[3]).ok
            if streamType ~= 'none' and streamType ~= 'stream' then return redis.error_reply('DLQ_WRONGTYPE') end
            if indexType ~= 'none' and indexType ~= 'hash' then return redis.error_reply('DLQ_INDEX_WRONGTYPE') end
            redis.call('XPENDING', KEYS[1], ARGV[1], ARGV[2], ARGV[2], 1)
            local id = redis.call('HGET', KEYS[3], ARGV[3])
            if id and #redis.call('XRANGE', KEYS[2], id, id) == 0 then id = false end
            if not id then
              id = redis.call('XADD', KEYS[2], '*', 'source', KEYS[1], 'group', ARGV[1],
                  'recordId', ARGV[2], 'failureId', ARGV[3], 'payloadBase64', ARGV[4], 'reason', ARGV[5])
              redis.call('HSET', KEYS[3], ARGV[3], id)
            end
            redis.call('XACK', KEYS[1], ARGV[1], ARGV[2])
            return id
            """, String.class);

    public String publishAndAcknowledge(FailedRecord failure) {
        String dlq = failure.getStreamKey() + ":dlq";
        String result = redisTemplate.execute(PUBLISH_AND_ACK,
                List.of(failure.getStreamKey(), dlq, dlq + ":index"), failure.getConsumerGroup(),
                failure.getRecordId(), failure.getId(), failure.getPayload(), failure.getReason());
        if (result == null) throw new IllegalStateException("Missing DLQ confirmation");
        return result;
    }
}
