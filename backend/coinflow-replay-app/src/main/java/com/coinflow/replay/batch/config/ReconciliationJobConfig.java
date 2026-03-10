package com.coinflow.replay.batch.config;

import com.coinflow.domain.log.service.MissingTickLogService;
import com.coinflow.domain.ohlc.domain.Ohlc1m;
import com.coinflow.domain.ohlc.service.Ohlc1mService;
import com.coinflow.domain.symbol.service.SymbolService;
import com.coinflow.replay.batch.processor.BinanceKlineProcessor;
import com.coinflow.replay.batch.reader.BinanceKlineReader;
import com.coinflow.replay.batch.writer.BinanceKlineWriter;
import com.coinflow.replay.client.BinanceKlineClient;
import com.coinflow.replay.client.dto.BinanceKline;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.Step;
import org.springframework.batch.core.configuration.annotation.StepScope;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;

@Configuration
public class ReconciliationJobConfig {

    public static final String JOB_NAME = "klineReconciliationJob";
    public static final String STEP_NAME = "klineReconciliationStep";

    private static final int CHUNK_SIZE = 500;

    @Bean
    public Job klineReconciliationJob(JobRepository jobRepository, Step klineReconciliationStep) {
        return new JobBuilder(JOB_NAME, jobRepository)
                .start(klineReconciliationStep)
                .build();
    }

    @Bean
    public Step klineReconciliationStep(JobRepository jobRepository,
            PlatformTransactionManager transactionManager,
            BinanceKlineReader klineReader,
            BinanceKlineProcessor klineProcessor,
            BinanceKlineWriter klineWriter) {
        return new StepBuilder(STEP_NAME, jobRepository)
                .<BinanceKline, Ohlc1m>chunk(CHUNK_SIZE, transactionManager)
                .reader(klineReader)
                .processor(klineProcessor)
                .writer(klineWriter)
                .faultTolerant() // 장애 허용 기능 활성화
                .skipLimit(100) // 최대 100건까지 에러 수용 (건너뛰기)
                .skip(Exception.class) // 예외 발생 시 해당 건 skip 후 진행
                .build();
    }

    @Bean
    @StepScope
    public BinanceKlineReader klineReader(
            BinanceKlineClient binanceKlineClient,
            @Value("#{jobParameters['symbol']}") String symbol,
            @Value("#{jobParameters['interval']}") String interval,
            @Value("#{jobParameters['startTime']}") Long startTime,
            @Value("#{jobParameters['endTime']}") Long endTime) {

        long start = startTime != null ? startTime : 0L;
        long end = endTime != null ? endTime : 0L;

        return new BinanceKlineReader(binanceKlineClient, symbol, interval, start, end);
    }

    @Bean
    @StepScope
    public BinanceKlineProcessor klineProcessor(
            @Value("#{jobParameters['symbol']}") String symbol,
            @Value("#{jobParameters['interval']}") String interval,
            SymbolService symbolService,
            Ohlc1mService ohlc1mService,
            MissingTickLogService missingTickLogService) {
        return new BinanceKlineProcessor(symbol, interval, symbolService, ohlc1mService, missingTickLogService);
    }

    @Bean
    @StepScope
    public BinanceKlineWriter klineWriter(Ohlc1mService ohlc1mService) {
        return new BinanceKlineWriter(ohlc1mService);
    }
}
