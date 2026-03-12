package com.coinflow.replay.batch.config;

import com.coinflow.domain.log.service.MissingTickLogService;
import com.coinflow.domain.ohlc.domain.AbstractOhlc;
import com.coinflow.domain.ohlc.service.Ohlc1mService;
import com.coinflow.domain.ohlc.service.Ohlc30mService;
import com.coinflow.domain.ohlc.service.Ohlc5mService;
import com.coinflow.domain.symbol.service.SymbolService;
import com.coinflow.replay.batch.common.ReconciliationBatchConstants;
import com.coinflow.replay.batch.model.RollupTarget;
import com.coinflow.replay.batch.partitioner.SymbolPartitioner;
import com.coinflow.replay.batch.processor.BinanceKlineProcessor;
import com.coinflow.replay.batch.processor.OhlcRollupProcessor;
import com.coinflow.replay.batch.processor.ReconciliationResult;
import com.coinflow.replay.batch.reader.BinanceKlineReader;
import com.coinflow.replay.batch.reader.OhlcRollupReader;
import com.coinflow.replay.batch.writer.BinanceKlineWriter;
import com.coinflow.replay.batch.writer.OhlcRollupWriter;
import com.coinflow.replay.client.BinanceKlineClient;
import com.coinflow.replay.client.dto.BinanceKline;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.Step;
import org.springframework.batch.core.configuration.annotation.StepScope;
import org.springframework.batch.core.job.builder.FlowBuilder;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.job.flow.Flow;
import org.springframework.batch.core.partition.support.Partitioner;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.TaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.transaction.PlatformTransactionManager;

import java.util.List;

@Configuration
public class ReconciliationJobConfig {

    public static final String JOB_NAME = ReconciliationBatchConstants.JOB_NAME;
    public static final String STEP_NAME = ReconciliationBatchConstants.WORKER_STEP_NAME;

    private static final int CHUNK_SIZE = ReconciliationBatchConstants.CHUNK_SIZE;

    @Value("${coinflow.batch.reconciliation.symbols:btcusdt}")
    private List<String> targetSymbols;

    private final Ohlc1mService ohlc1mService;
    private final Ohlc5mService ohlc5mService;
    private final Ohlc30mService ohlc30mService;
    private final SymbolService symbolService;
    private final MissingTickLogService missingTickLogService;

    public ReconciliationJobConfig(Ohlc1mService ohlc1mService, Ohlc5mService ohlc5mService,
            Ohlc30mService ohlc30mService, SymbolService symbolService,
            MissingTickLogService missingTickLogService) {
        this.ohlc1mService = ohlc1mService;
        this.ohlc5mService = ohlc5mService;
        this.ohlc30mService = ohlc30mService;
        this.symbolService = symbolService;
        this.missingTickLogService = missingTickLogService;
    }

    @Bean
    public Job klineReconciliationJob(JobRepository jobRepository, Step managerStep) {
        return new JobBuilder(JOB_NAME, jobRepository)
                .start(managerStep)
                .build();
    }

    @Bean
    public Step managerStep(JobRepository jobRepository, Step workerStep, Partitioner symbolPartitioner,
            TaskExecutor taskExecutor) {
        return new StepBuilder(ReconciliationBatchConstants.MANAGER_STEP_NAME, jobRepository)
                .partitioner(workerStep.getName(), symbolPartitioner)
                .step(workerStep)
                .gridSize(targetSymbols.size())
                .taskExecutor(taskExecutor)
                .build();
    }

    @Bean
    public Step workerStep(JobRepository jobRepository, Flow workerFlow) {
        return new StepBuilder(ReconciliationBatchConstants.WORKER_STEP_SINGLE_NAME, jobRepository)
                .flow(workerFlow)
                .build();
    }

    @Bean
    public Flow workerFlow(Step klineReconciliationStep, Step ohlc5mRollupStep, Step ohlc30mRollupStep) {
        return new FlowBuilder<Flow>(ReconciliationBatchConstants.WORKER_FLOW_NAME)
                .start(klineReconciliationStep)
                .next(ohlc5mRollupStep)
                .next(ohlc30mRollupStep)
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
        executor.setThreadNamePrefix(ReconciliationBatchConstants.BATCH_THREAD_PREFIX);
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
                .build();
    }

    @Bean
    public Step ohlc5mRollupStep(JobRepository jobRepository,
            PlatformTransactionManager transactionManager,
            OhlcRollupReader rollup5mReader,
            OhlcRollupProcessor rollupProcessor,
            OhlcRollupWriter rollupWriter) {
        return new StepBuilder(ReconciliationBatchConstants.ROLLUP_5M_STEP_NAME, jobRepository)
                .<RollupTarget, AbstractOhlc>chunk(CHUNK_SIZE, transactionManager)
                .reader(rollup5mReader)
                .processor(rollupProcessor)
                .writer(rollupWriter)
                .build();
    }

    @Bean
    public Step ohlc30mRollupStep(JobRepository jobRepository,
            PlatformTransactionManager transactionManager,
            OhlcRollupReader rollup30mReader,
            OhlcRollupProcessor rollupProcessor,
            OhlcRollupWriter rollupWriter) {
        return new StepBuilder(ReconciliationBatchConstants.ROLLUP_30M_STEP_NAME, jobRepository)
                .<RollupTarget, AbstractOhlc>chunk(CHUNK_SIZE, transactionManager)
                .reader(rollup30mReader)
                .processor(rollupProcessor)
                .writer(rollupWriter)
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
    public BinanceKlineWriter klineWriter() {
        return new BinanceKlineWriter(ohlc1mService, missingTickLogService);
    }

    @Bean
    @StepScope
    public OhlcRollupReader rollup5mReader(
            @Value("#{stepExecutionContext['" + ReconciliationBatchConstants.PARAM_SYMBOL + "']}") String symbol,
            @Value("#{jobParameters['" + ReconciliationBatchConstants.PARAM_START_TIME + "']}") Long startTime,
            @Value("#{jobParameters['" + ReconciliationBatchConstants.PARAM_END_TIME + "']}") Long endTime) {
        return createRollupReader(symbol, ReconciliationBatchConstants.INTERVAL_5M_MINUTES, startTime, endTime);
    }

    @Bean
    @StepScope
    public OhlcRollupReader rollup30mReader(
            @Value("#{stepExecutionContext['" + ReconciliationBatchConstants.PARAM_SYMBOL + "']}") String symbol,
            @Value("#{jobParameters['" + ReconciliationBatchConstants.PARAM_START_TIME + "']}") Long startTime,
            @Value("#{jobParameters['" + ReconciliationBatchConstants.PARAM_END_TIME + "']}") Long endTime) {
        return createRollupReader(symbol, ReconciliationBatchConstants.INTERVAL_30M_MINUTES, startTime, endTime);
    }

    private OhlcRollupReader createRollupReader(String symbol, int intervalMinutes, Long startTime, Long endTime) {
        long start = startTime != null ? startTime : 0L;
        long end = endTime != null ? endTime : 0L;
        return new OhlcRollupReader(symbol, intervalMinutes, start, end, symbolService);
    }

    @Bean
    @StepScope
    public OhlcRollupProcessor rollupProcessor() {
        return new OhlcRollupProcessor(ohlc1mService, ohlc5mService, ohlc30mService);
    }

    @Bean
    @StepScope
    public OhlcRollupWriter rollupWriter() {
        return new OhlcRollupWriter(ohlc5mService, ohlc30mService);
    }
}
