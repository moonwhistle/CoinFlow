package com.coinflow.ticker.publisher;

/**
 * 실시간 현재가 payload를 클라이언트 전달 채널로 발행합니다.
 */
public interface TickerPublisher {

    void publish(String tickerPayload);
}
