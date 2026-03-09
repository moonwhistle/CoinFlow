package com.coinflow.replay.client;

import com.coinflow.replay.client.dto.BinanceKline;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.client.RestTemplate;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BinanceKlineClientTest {

    @Mock
    private RestTemplate restTemplate;

    private BinanceKlineClient client;

    @BeforeEach
    void setUp() {
        client = new BinanceKlineClient(restTemplate, "https://api.binance.com");
    }

    @Test
    void fetchKlines_parsesResponseCorrectly() {
        // given
        Object[][] mockResponse = new Object[][] {
                {
                        1672531200000L, // openTime
                        "16500.10", // open
                        "16550.50", // high
                        "16450.00", // low
                        "16510.20", // close
                        "100.5", // volume
                        1672531259999L, // closeTime
                        "1655000.00", // quoteAssetVolume
                        1500, // numberOfTrades
                        "50.2", // takerBuyBaseAssetVolume
                        "825000.00" // takerBuyQuoteAssetVolume
                }
        };

        when(restTemplate.getForObject(any(String.class), eq(Object[][].class)))
                .thenReturn(mockResponse);

        // when
        List<BinanceKline> result = client.fetchKlines("BTCUSDT", "1m", 1672531200000L, 1672531259999L, 1);

        // then
        assertThat(result).hasSize(1);
        BinanceKline kline = result.get(0);
        assertThat(kline.openTime()).isEqualTo(1672531200000L);
        assertThat(kline.open()).isEqualTo(new BigDecimal("16500.10"));
        assertThat(kline.close()).isEqualTo(new BigDecimal("16510.20"));
        assertThat(kline.volume()).isEqualTo(new BigDecimal("100.5"));
    }

    @Test
    void fetchKlines_returnsEmptyList_whenResponseIsNull() {
        // given
        when(restTemplate.getForObject(any(String.class), eq(Object[][].class)))
                .thenReturn(null);

        // when
        List<BinanceKline> result = client.fetchKlines("BTCUSDT", "1m", 1672531200000L, 1672531259999L, 1);

        // then
        assertThat(result).isEmpty();
    }
}
