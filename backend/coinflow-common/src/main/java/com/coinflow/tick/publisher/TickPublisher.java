package com.coinflow.tick.publisher;

/**
 * 틱 데이터를 전송하기 위한 공통 인터페이스입니다.
 */
public interface TickPublisher {

    /**
     * 원본 틱은 Stream에 저장하고, 현재가 payload는 Pub/Sub으로 전파합니다.
     * 구현체는 두 Redis 명령 사이에 애플리케이션 장애 구간이 없도록 원자적으로 실행해야 합니다.
     */
    void publish(byte[] rawData, String tickerPayload);
}
