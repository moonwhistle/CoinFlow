package com.coinflow.replay;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.retry.annotation.EnableRetry;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
@EnableRetry
public class ReplayApplication {
    public static void main(String[] args) {
        SpringApplication.run(ReplayApplication.class, args);
    }
}
