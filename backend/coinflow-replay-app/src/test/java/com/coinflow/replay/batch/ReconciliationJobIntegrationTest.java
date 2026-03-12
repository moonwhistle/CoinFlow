package com.coinflow.replay.batch;

import com.coinflow.domain.log.domain.MissingTickLog;
import com.coinflow.domain.log.domain.vo.ReconciliationReason;
import com.coinflow.domain.log.repository.MissingTickLogRepository;
import com.coinflow.domain.ohlc.domain.Ohlc1m;
import com.coinflow.domain.ohlc.domain.Ohlc5m;
import com.coinflow.domain.ohlc.domain.Ohlc30m;
import com.coinflow.domain.ohlc.repository.Ohlc1mRepository;
import com.coinflow.domain.ohlc.repository.Ohlc5mRepository;
import com.coinflow.domain.ohlc.repository.Ohlc30mRepository;
import com.coinflow.domain.symbol.domain.Symbol;
import com.coinflow.domain.symbol.domain.vo.MarketType;
import com.coinflow.domain.symbol.repository.SymbolRepository;
import com.coinflow.replay.batch.common.ReconciliationBatchConstants;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.client.AutoConfigureMockRestServiceServer;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import org.springframework.web.client.RestTemplate;

@SpringBootTest
@ActiveProfiles("test")
class ReconciliationJobIntegrationTest {

        @Autowired
        private JobLauncher jobLauncher;

        @Autowired
        private Job klineReconciliationJob;

        @Autowired
        private Ohlc1mRepository ohlc1mRepository;

        @Autowired
        private Ohlc5mRepository ohlc5mRepository;

        @Autowired
        private Ohlc30mRepository ohlc30mRepository;

        @Autowired
        private SymbolRepository symbolRepository;

        @Autowired
        private MissingTickLogRepository missingTickLogRepository;

        @Autowired
        private RestTemplate restTemplate;

        @Autowired
        private ObjectMapper objectMapper;

        private MockRestServiceServer mockServer;
        private Symbol testSymbol;

        @BeforeEach
        void setUp() {
                mockServer = MockRestServiceServer.createServer(restTemplate);
                ohlc30mRepository.deleteAll();
                ohlc5mRepository.deleteAll();
                ohlc1mRepository.deleteAll();
                missingTickLogRepository.deleteAll();
                symbolRepository.deleteAll();

                testSymbol = symbolRepository.save(Symbol.builder()
                                .symbol("btcusdt")
                                .exchange("binance")
                                .name("Bitcoin")
                                .active(true)
                                .marketType(MarketType.SPOT)
                                .build());
        }

        @Test
        @DisplayName("통합 테스트: 1분봉 데이터 보정 후 상위 타임프레임(5m, 30m) 롤업이 연쇄적으로 수행된다")
        void reconciliation_FullFlowTest() throws Exception {
                // given
                // 10:00:00 시작 시점 (UTC)
                long startTimestamp = 1710237600000L; // 2024-03-12 10:00:00
                LocalDateTime bucketTime1000 = ReconciliationBatchConstants.toLocalDateTime(startTimestamp);

                // 1. 기존 DB에 잘못된 10:00 1분봉 데이터 존재
                ohlc1mRepository.save(Ohlc1m.builder()
                                .symbol(testSymbol)
                                .bucketTime(bucketTime1000)
                                .open(new BigDecimal("50000"))
                                .high(new BigDecimal("51000"))
                                .low(new BigDecimal("49000"))
                                .close(new BigDecimal("50500"))
                                .volume(100L)
                                .build());

                // 2. 바이낸스 API Mock 응답: 10:00 ~ 10:04 (5분치)
                // 10:00이 보정되어야 함 (50000 -> 60000)
                // BinanceKline.fromArray는 11개의 요소를 필요로 함
                Object[][] mockApiResponse = new Object[][] {
                                { startTimestamp, "60000", "61000", "59000", "60500", "100", startTimestamp + 59999,
                                                "0",
                                                0, "0", "0" },
                                { startTimestamp + 60000, "60500", "61500", "60000", "61000", "200",
                                                startTimestamp + 119999, "0", 0, "0", "0" },
                                { startTimestamp + 120000, "61000", "62000", "60500", "61500", "300",
                                                startTimestamp + 179999, "0", 0, "0", "0" },
                                { startTimestamp + 180000, "61500", "62500", "61000", "62000", "400",
                                                startTimestamp + 239999, "0", 0, "0", "0" },
                                { startTimestamp + 240000, "62000", "63000", "61500", "62500", "500",
                                                startTimestamp + 299999, "0", 0, "0", "0" }
                };

                mockServer.expect(requestTo(org.hamcrest.Matchers.containsString("/api/v3/klines")))
                                .andRespond(withSuccess(objectMapper.writeValueAsString(mockApiResponse),
                                                MediaType.APPLICATION_JSON));

                // 3. 배치 잡 실행 (10:00 ~ 10:30.000 구간 보정 요청)
                // endTime을 1800000(30분)으로 설정해야 30분봉 롤업 리더가 10:00~10:30 버킷을 '닫힌 버킷'으로 인식함
                JobParameters params = new JobParametersBuilder()
                                .addString(ReconciliationBatchConstants.PARAM_SYMBOL, "btcusdt")
                                .addString(ReconciliationBatchConstants.PARAM_INTERVAL, "1m")
                                .addLong(ReconciliationBatchConstants.PARAM_START_TIME, startTimestamp)
                                .addLong(ReconciliationBatchConstants.PARAM_END_TIME, startTimestamp + 1800000)
                                .addLong(ReconciliationBatchConstants.PARAM_RUN_ID, System.currentTimeMillis())
                                .toJobParameters();

                // when
                jobLauncher.run(klineReconciliationJob, params);

                // then
                // 검증 1: 1분봉 보정 확인 (10:00 데이터)
                Ohlc1m m1 = ohlc1mRepository.findBySymbolIdAndBucketTime(testSymbol.getId(), bucketTime1000)
                                .orElseThrow();
                assertThat(m1.getOpenPrice()).isEqualByComparingTo("60000");

                // 검증 2: 5분봉 롤업 자동 생성 확인 (10:00 ~ 10:04 집계)
                // Open: 60000 (보정된 값), Close: 62500, High: 63000, Low: 59000, Volume: 1500
                Ohlc5m m5 = ohlc5mRepository
                                .findBySymbolIdAndBucketTime(testSymbol.getId(), bucketTime1000).orElseThrow();
                assertThat(m5.getOpenPrice()).isEqualByComparingTo("60000");
                assertThat(m5.getClosePrice()).isEqualByComparingTo("62500");
                assertThat(m5.getHighPrice()).isEqualByComparingTo("63000");
                assertThat(m5.getLowPrice()).isEqualByComparingTo("59000");
                assertThat(m5.getVolume()).isEqualTo(1500L);

                // 검증 3: 30분봉 롤업 자동 생성 확인 (10:00 버킷)
                Ohlc30m m30 = ohlc30mRepository
                                .findBySymbolIdAndBucketTime(testSymbol.getId(), bucketTime1000).orElseThrow();
                assertThat(m30.getOpenPrice()).isEqualByComparingTo("60000");
                assertThat(m30.getVolume()).isEqualTo(1500L); // 5분치만 있으므로 1500
        }
}
