package com.coinflow.replay;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.retry.annotation.EnableRetry;
import org.springframework.scheduling.annotation.EnableScheduling;

import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

@SpringBootApplication(scanBasePackages = "com.coinflow")
@EnableJpaRepositories(basePackages = "com.coinflow")
@EntityScan(basePackages = "com.coinflow")
@EnableScheduling
@EnableRetry
public class ReplayApplication {
    public static void main(String[] args) {
        SpringApplication.run(ReplayApplication.class, args);
    }
}
