package com.coinflow.util;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

/**
 * Tick 시간(Instant)을 OHLC 버킷 시작 시각으로 정규화.
 * 저장/버킷 기준 시간대는 UTC 고정
 * 버킷은 항상 시작 시각으로 표현
 */
public final class TimeBucket {

    private static final ZoneOffset BUCKET_ZONE = ZoneOffset.UTC;
    private static final int ZERO_SECOND = 0;
    private static final int ZERO_NANO = 0;
    private static final int FIVE_MINUTE_BUCKET = 5;
    private static final int THIRTY_MINUTE_BUCKET = 30;

    private TimeBucket() {
    }

    /** Tick 발생 시각 > 1분 봉 시작 시각 (UTC) */
    public static LocalDateTime to1m(Instant eventTime) {
        LocalDateTime time = LocalDateTime.ofInstant(eventTime, BUCKET_ZONE);

        return truncateToMinute(time);
    }

    /** 1분 봉 기준 > 5분 봉 */
    public static LocalDateTime to5m(LocalDateTime bucket1m) {
        return truncateToMinuteBucket(bucket1m, FIVE_MINUTE_BUCKET);
    }

    /** 1분 봉 기준 > 30분 봉 */
    public static LocalDateTime to30m(LocalDateTime bucket1m) {
        return truncateToMinuteBucket(bucket1m, THIRTY_MINUTE_BUCKET);
    }

    private static LocalDateTime truncateToMinute(LocalDateTime time) {
        return time.withSecond(ZERO_SECOND)
                .withNano(ZERO_NANO);
    }

    private static LocalDateTime truncateToMinuteBucket(LocalDateTime time, int bucketSizeMinutes) {
        int minute = (time.getMinute() / bucketSizeMinutes) * bucketSizeMinutes;

        return time.withMinute(minute)
                .withSecond(ZERO_SECOND)
                .withNano(ZERO_NANO);
    }
}
