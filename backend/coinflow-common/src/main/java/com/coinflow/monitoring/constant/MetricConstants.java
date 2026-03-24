package com.coinflow.monitoring.constant;

public final class MetricConstants {

    private MetricConstants() {
        // utility class
    }

    // Consumer: Stream ACK 관련 지표
    public static final String STREAM_ACK_COUNT = "stream.ack.count";
    public static final String STREAM_ACK_LATENCY = "stream.ack.latency";

    // Collector: 유입량 및 발행 지표
    public static final String WEBSOCKET_RECEIVE_COUNT = "tick.receive.count";
    public static final String STREAM_PUBLISH_LATENCY = "stream.publish.latency";

    // Tag Keys (다차원 분석용 태그 상수화)
    public static final String TAG_MODULE = "module";
    public static final String TAG_TYPE = "type";
}
