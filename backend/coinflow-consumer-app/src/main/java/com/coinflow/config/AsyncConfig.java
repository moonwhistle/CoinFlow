package com.coinflow.config;

import com.coinflow.config.properties.AsyncDbPersistProperties;
import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor.CallerRunsPolicy;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.retry.annotation.EnableRetry;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.beans.factory.ObjectProvider;

@Configuration
@EnableAsync
@EnableRetry
@RequiredArgsConstructor
@EnableConfigurationProperties(AsyncDbPersistProperties.class)
public class AsyncConfig {

    private final AsyncDbPersistProperties properties;

    @Bean(name = "dbPersistExecutor")
    public ThreadPoolTaskExecutor dbPersistExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();

        executor.setCorePoolSize(properties.corePoolSize()); // 기본으로 유지할 스레드 수
        executor.setMaxPoolSize(properties.maxPoolSize()); // 최대 스레드 수
        executor.setQueueCapacity(properties.queueCapacity()); // 대기 큐 크기
        executor.setKeepAliveSeconds(properties.keepAliveSeconds()); // 유휴 스레드 대기 시간
        executor.setAllowCoreThreadTimeOut(true); // Core 스레드도 유휴 시 반납 (자원 효율화)
        executor.setThreadNamePrefix(properties.threadNamePrefix()); // 스레드 이름 접두사
        executor.setRejectedExecutionHandler(new CallerRunsPolicy()); // 큐가 가득 찼을 때의 정책: 호출한 스레드에서 직접 실행
        executor.initialize();

        return executor;
    }
}
