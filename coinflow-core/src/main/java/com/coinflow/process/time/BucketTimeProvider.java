package com.coinflow.process.time;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class BucketTimeProvider {

    private static final ZoneOffset UTC = ZoneOffset.UTC;

    private final Clock clock;

    public LocalDateTime nowUtc() {
        return LocalDateTime.now(clock)
                .atOffset(UTC)
                .toLocalDateTime();
    }
}
