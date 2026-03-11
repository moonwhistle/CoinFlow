package com.coinflow.replay.batch.config;

import com.coinflow.domain.log.service.MissingTickLogService;
import com.coinflow.domain.ohlc.service.Ohlc1mService;
import com.coinflow.domain.symbol.service.SymbolService;
import com.coinflow.replay.batch.common.ReconciliationBatchConstants;
import com.coinflow.replay.batch.processor.BinanceKlineProcessor;
import com.coinflow.replay.batch.reader.BinanceKlineReader;
import com.coinflow.replay.batch.writer.BinanceKlineWriter;
import com.coinflow.replay.batch.processor.ReconciliationResult;
import com.coinflow.replay.client.BinanceKlineClient;
import com.coinflow.replay.client.dto.BinanceKline;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.Step;
import org.springframework.batch.core.configuration.annotation.StepScope;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.partition.support.Partitioner;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.TaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.transaction.PlatformTransactionManager;
import com.coinflow.replay.batch.partitioner.SymbolPartitioner;

import java.util.List;

@Configuration
public class ReconciliationJobConfig {

    public static final String JOB_NAME = ReconciliationBatchConstants.JOB_NAME;
    public static final String STEP_NAME = ReconciliationBatchConstants.WORKER_STEP_NAME;

    private static final int CHUNK_SIZE = 500;

    @Value("${coinflow.batch.reconciliation.symbols:btcusdt}")
    private List<String> targetSymbols;

    @Bean
    public Job klineReconciliationJob(JobRepository jobRepository, Step managerStep) {
        return new JobBuilder(JOB_NAME, jobRepository)
                .start(managerStep)
                .build();
    }

    @Bean
    public Step managerStep(JobRepository jobRepository, Step klineReconciliationStep, Partitioner symbolPartitioner,
            TaskExecutor taskExecutor) {
        return new StepBuilder(ReconciliationBatchConstants.MANAGER_STEP_NAME, jobRepository)
                .partitioner(klineReconciliationStep.getName(), symbolPartitioner)
                .step(klineReconciliationStep)
                .gridSize(targetSymbols.size())
                .taskExecutor(taskExecutor)
                .build();
    }

    @Bean
    public Partitioner symbolPartitioner() {
        return new SymbolPartitioner(targetSymbols);
    }

    @Bean
    public TaskExecutor taskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(ReconciliationBatchConstants.DEFAULT_THREAD_POOL_SIZE);
        executor.setMaxPoolSize(ReconciliationBatchConstants.DEFAULT_THREAD_POOL_SIZE);
        executor.setThreadNamePrefix("batch-thread-");
        executor.initialize();
        return executor;
    }

    @Bean
    public Step klineReconciliationStep(JobRepository jobRepository,
            PlatformTransactionManager transactionManager,
            BinanceKlineReader klineReader,
            BinanceKlineProcessor klineProcessor,
            BinanceKlineWriter klineWriter) {
        return new StepBuilder(STEP_NAME, jobRepository)
                .<BinanceKline, ReconciliationResult>chunk(CHUNK_SIZE, transactionManager)
                .reader(klineReader)
                .processor(klineProcessor)
                .writer(klineWriter)
                .faultTolerant()
                .skipLimit(100)
                .skip(Exception.class)
                .build();
    }

    @Bean
    @StepScope
    public BinanceKlineReader klineReader(
            BinanceKlineClient binanceKlineClient,
            @Value("#{stepExecutionContext['" + ReconciliationBatchConstants.PARAM_SYMBOL + "']}") String symbol,
            @Value("#{jobParameters['" + ReconciliationBatchConstants.PARAM_INTERVAL + "']}") String interval,
            @Value("#{jobParameters['" + ReconciliationBatchConstants.PARAM_START_TIME + "']}") Long startTime,
            @Value("#{jobParameters['" + ReconciliationBatchConstants.PARAM_END_TIME + "']}") Long endTime) {

        long start = startTime != null ? startTime : 0L;
        long end = endTime != null ? endTime : 0L;

        return new BinanceKlineReader(binanceKlineClient, symbol, interval, start, end);
    }

    @Bean
    @StepScope
    public BinanceKlineProcessor klineProcessor(
            @Value("#{stepExecutionContext['" + ReconciliationBatchConstants.PARAM_SYMBOL + "']}") String symbol,
            @Value("#{jobParameters['" + ReconciliationBatchConstants.PARAM_INTERVAL + "']}") String interval,
            SymbolService symbolService,
            Ohlc1mService ohlc1mService) {
        return new BinanceKlineProcessor(symbol, interval, symbolService, ohlc1mService);
    }

    @Bean
    @StepScope
    public BinanceKlineWriter klineWriter(Ohlc1mService ohlc1mService, MissingTickLogService missingTickLogService) {
        return new BinanceKlineWriter(ohlc1mService, missingTickLogService);
    }
}
