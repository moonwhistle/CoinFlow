package com.coinflow.domain.recovery.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "verified_candle")
@Getter @Setter @NoArgsConstructor
public class VerifiedCandle {
    @Id @Column(length = 180) private String id;
    private String symbol;
    private String intervalName;
    private long bucket;
    private long verifiedAt;
    private boolean cachePublished;

    public static String key(String symbol, String interval, long bucket) {
        return symbol.toLowerCase(java.util.Locale.ROOT) + ":" + interval + ":" + bucket;
    }
}
