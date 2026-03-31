package com.coinflow.tick.publisher;

/**
 * 틱 데이터를 전송하기 위한 공통 인터페이스입니다.
 */
public interface TickPublisher {

    /**
     * 바이너리 데이터를 직접 전송합니다. (Zero-POJO 최적화 방식)
     */
    void publish(byte[] rawData);
}
