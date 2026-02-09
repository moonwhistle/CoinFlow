package com.coinflow.aggregation.service.rollup;

import com.coinflow.aggregation.event.Ohlc1mFlushedEvent;
import com.coinflow.domain.ohlc.domain.Ohlc1m;
import com.coinflow.domain.ohlc.domain.Ohlc5m;
import com.coinflow.domain.ohlc.repository.Ohlc1mRepository;
import com.coinflow.domain.ohlc.repository.Ohlc5mRepository;
import com.coinflow.domain.symbol.domain.Symbol;
import com.coinflow.domain.symbol.repository.SymbolRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
class OhlcRollupIntegrationTest {

    @Autowired
    private SymbolRepository symbolRepository;

    @Autowired
    private Ohlc1mRepository ohlc1mRepository;

    @Autowired
    private Ohlc5mRepository ohlc5mRepository;

    @Autowired
    private Ohlc5mFlushedService ohlc5mFlushedService;

    // Mock Redis to avoid connection issues during context load
    @MockBean
    private RedisConnectionFactory redisConnectionFactory;

    // We should mock things that might try to connect to Redis on startup
    // For example, if there is a listener container.
    // However, @MockBean RedisConnectionFactory usually prevents the container from
    // starting or makes it fail gracefully?
    // Let's hope so.

    @Test
    void testOhlc5mRollup_Success() {
        // Given
        Symbol symbol = symbolRepository.save(Symbol.builder().symbol("BTCUSDT").build());
        Long symbolId = symbol.getId();

        // Prepare test data: 3 minutes of 1m candles
        // 5m Bucket start: 2024-01-01 10:00:00
        // Candles at: 10:00, 10:01, 10:02
        LocalDateTime bucketStart5m = LocalDateTime.of(2024, 1, 1, 10, 0, 0);

        Ohlc1m c1 = createOhlc1m(symbol, bucketStart5m, 100, 110, 90, 105, 10);
        Ohlc1m c2 = createOhlc1m(symbol, bucketStart5m.plusMinutes(1), 105, 115, 100, 110, 20);
        Ohlc1m c3 = createOhlc1m(symbol, bucketStart5m.plusMinutes(2), 110, 120, 105, 115, 30);

        ohlc1mRepository.saveAll(List.of(c1, c2, c3));

        // When
        // Simulate the event that triggers rollup for 5m.
        // The event carries the time of the flushed 1m candle.
        // Let's say we flushed the 10:02 candle.
        Ohlc1mFlushedEvent event = new Ohlc1mFlushedEvent(
                symbolId,
                bucketStart5m.plusMinutes(2));

        ohlc5mFlushedService.onOhlc1mFlushed(event);

        // Then
        // Verify 5m data is created
        List<Ohlc5m> result = ohlc5mRepository.findAll();
        assertThat(result).hasSize(1);

        Ohlc5m ohlc5m = result.get(0);
        assertThat(ohlc5m.getSymbol().getId()).isEqualTo(symbolId);
        assertThat(ohlc5m.getBucketTime()).isEqualTo(bucketStart5m);
        assertThat(ohlc5m.getOpen()).isEqualByComparingTo("100"); // from c1
        assertThat(ohlc5m.getHigh()).isEqualByComparingTo("120"); // max(110, 115, 120) -> 120
        assertThat(ohlc5m.getLow()).isEqualByComparingTo("90"); // min(90, 100, 105) -> 90
        assertThat(ohlc5m.getClose()).isEqualByComparingTo("115"); // from c3 (latest)
        assertThat(ohlc5m.getVolume()).isEqualTo(60L); // 10+20+30
    }

    private Ohlc1m createOhlc1m(Symbol symbol, LocalDateTime time, double o, double h, double l, double c, long v) {
        return Ohlc1m.builder()
                .symbol(symbol)
                .bucketTime(time)
                .open(BigDecimal.valueOf(o))
                .high(BigDecimal.valueOf(h))
                .low(BigDecimal.valueOf(l))
                .close(BigDecimal.valueOf(c))
                .volume(v)
                .build();
    }
}
