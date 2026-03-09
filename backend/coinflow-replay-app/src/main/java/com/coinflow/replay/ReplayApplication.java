package com.coinflow.replay;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.retry.annotation.EnableRetry;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication(scanBasePackages = "com.coinflow")
@EnableScheduling
@EnableRetry
public class ReplayApplication {
    public static void main(String[] args) {
        System.setProperty("spring.config.name", "application-replay");
        SpringApplication.run(ReplayApplication.class, args);
    }
}
