package com.coinflow.replay.batch;

import com.coinflow.domain.log.domain.MissingTickLog;
import com.coinflow.domain.log.domain.vo.ReconciliationReason;
import com.coinflow.domain.log.repository.MissingTickLogRepository;
import com.coinflow.domain.ohlc.domain.Ohlc1m;
import com.coinflow.domain.ohlc.repository.Ohlc1mRepository;
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
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
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
    @DisplayName("통합 테스트: DB의 잘못된 가격 정보를 바이낸스 데이터로 보정하고 로그를 남긴다")
    void reconciliation_CorrectsMismatchedData() throws Exception {
        // given
        long timestamp = (System.currentTimeMillis() / 60000) * 60000 - 120000; // 2분 전
        LocalDateTime bucketTime = LocalDateTime.ofInstant(Instant.ofEpochMilli(timestamp), ZoneId.systemDefault());

        // 1. DB에 잘못된 데이터 삽입 (Open Price가 50000인데 바이낸스는 60000인 상황 가정)
        ohlc1mRepository.save(Ohlc1m.builder()
                .symbol(testSymbol)
                .bucketTime(bucketTime)
                .open(new BigDecimal("50000"))
                .high(new BigDecimal("61000"))
                .low(new BigDecimal("49000"))
                .close(new BigDecimal("60500"))
                .volume(100L)
                .build());

        // 2. 바이낸스 API Mock 응답 설정
        Object[][] mockApiResponse = new Object[][] {
                {
                        timestamp, "60000.00", "61000.00", "59000.00", "60500.00", "100.0",
                        timestamp + 59999, "6050000.00", 100, "50.0", "3025000.00"
                }
        };

        mockServer.expect(requestTo(org.hamcrest.Matchers.containsString("/api/v3/klines")))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(objectMapper.writeValueAsString(mockApiResponse), MediaType.APPLICATION_JSON));

        // 3. 배치 잡 실행
        JobParameters params = new JobParametersBuilder()
                .addString(ReconciliationBatchConstants.PARAM_SYMBOL, "btcusdt")
                .addString(ReconciliationBatchConstants.PARAM_INTERVAL, "1m")
                .addLong(ReconciliationBatchConstants.PARAM_START_TIME, timestamp)
                .addLong(ReconciliationBatchConstants.PARAM_END_TIME, timestamp + 59999)
                .addLong(ReconciliationBatchConstants.PARAM_RUN_ID, System.currentTimeMillis())
                .toJobParameters();

        // when
        jobLauncher.run(klineReconciliationJob, params);

        // then
        // 1. DB 데이터가 보정되었는지 확인 (50000 -> 60000)
        Ohlc1m corrected = ohlc1mRepository.findBySymbolIdAndBucketTime(testSymbol.getId(), bucketTime)
                .orElseThrow();
        assertThat(corrected.getOpenPrice()).isEqualByComparingTo("60000");

        // 2. MissingTickLog가 생성되었는지 확인
        List<MissingTickLog> logs = missingTickLogRepository.findAll();
        assertThat(logs).hasSize(1);
        assertThat(logs.get(0).getReason()).isEqualTo(ReconciliationReason.MISMATCH);
        assertThat(logs.get(0).getActualClosePrice()).isEqualByComparingTo("60500");
    }
}
