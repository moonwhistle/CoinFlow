package com.coinflow.replay.client;

import com.coinflow.replay.client.dto.BinanceKline;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@SpringBootTest
@ActiveProfiles("test")
class BinanceKlineClientRetryTest {

    @Autowired
    private BinanceKlineClient client;

    @MockBean
    private RestTemplate restTemplate;

    @Test
    void fetchKlines_retriesOnRestClientException_andSucceeds() {
        // given
        Object[][] successResponse = new Object[][] {
                {
                        1672531200000L, "16500.10", "16550.50", "16450.00", "16510.20", "100.5",
                        1672531259999L, "1655000.00", 1500, "50.2", "825000.00"
                }
        };

        // 처음 2번은 에러가 나고, 3번째에 성공하는 Mock 설정
        when(restTemplate.getForObject(any(String.class), eq(Object[][].class)))
                .thenThrow(new RestClientException("Simulated API Error 1"))
                .thenThrow(new RestClientException("Simulated API Error 2"))
                .thenReturn(successResponse);

        // when
        long start = System.currentTimeMillis();
        List<BinanceKline> result = client.fetchKlines("BTCUSDT", "1m", 1672531200000L, 1672531259999L, 1);
        long elapsed = System.currentTimeMillis() - start;

        // then
        assertThat(result).hasSize(1);
        // 총 3번 호출되었는지 검증 (재시도 로직 작동 확인)
        verify(restTemplate, times(3)).getForObject(any(String.class), eq(Object[][].class));

        // 재시도 백오프(delay=1000, multiplier=2.0) 설정에 따라
        // 약 1000 + 2000 = 3000ms 의 시간이 소요되었는지 확인
        assertThat(elapsed).isGreaterThanOrEqualTo(3000L);
    }
}
