package com.coinflow.monitoring.constant;

public final class MetricConstants {

    private MetricConstants() {
        // utility class
    }

    // Consumer: Stream ACK 관련 지표
    public static final String STREAM_ACK_COUNT = "stream.ack.count";
    public static final String STREAM_ACK_LATENCY = "stream.ack.latency";
    public static final String STREAM_BACKLOG_COUNT = "stream.backlog.count";
    public static final String STREAM_PEL_COUNT = "stream.pel.count";
    public static final String REDIS_COMMAND_COUNT = "redis.command.count";

    // Collector: 유입량 및 발행 지표
    public static final String WEBSOCKET_RECEIVE_COUNT = "tick.receive.count";
    public static final String STREAM_PUBLISH_LATENCY = "stream.publish.latency";

    // Consumer: 틱 처리 전체 지표
    public static final String TICK_PROCESS_LATENCY = "tick.process.latency";
    public static final String TICK_MAIN_THREAD_LATENCY = "tick.main.thread.latency";
    public static final String TICK_PROCESS_STATUS = "tick.process.status";

    // Consumer: 전파 지표
    // 제거됨 (비즈니스 요구사항에 따라 대시보드에서 제외)

    // Tag Keys (다차원 분석용 태그 상수화)
    public static final String TAG_MODULE = "module";
    public static final String TAG_TYPE = "type";
    public static final String TAG_COMMAND = "command";
    public static final String TAG_STATUS = "status";
    public static final String TAG_FLUSH_REASON = "flush_reason";

    // Tag Values
    public static final String VALUE_SUCCESS = "success";
    public static final String VALUE_FAILURE = "failure";
    public static final String VALUE_MODULE_CONSUMER = "consumer";
    public static final String VALUE_NA = "NA";
    public static final String VALUE_FLUSH_SIZE = "size";
    public static final String VALUE_FLUSH_INTERVAL = "interval";

}
