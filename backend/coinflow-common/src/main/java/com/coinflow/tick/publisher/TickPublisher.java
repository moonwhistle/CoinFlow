package com.coinflow.tick.publisher;

/**
 * 틱 데이터를 전송하기 위한 공통 인터페이스입니다.
 */
public interface TickPublisher {

    /**
     * 캔들 집계에 사용할 원본 틱을 메시지 스트림에 저장합니다.
     */
    void publish(byte[] rawData);
}
