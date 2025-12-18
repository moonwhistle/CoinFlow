package com.coinflow.domain.rollup.domain;

import static com.coinflow.common.exception.CoreErrorCode.ROLLUP_CHECKPOINT_BACKWARD;
import static com.coinflow.common.exception.CoreErrorCode.ROLLUP_INVALID_INTERVAL;
import static com.coinflow.common.exception.CoreErrorCode.ROLLUP_TIME_RANGE_ERROR;

import com.coinflow.common.exception.CoreException;
import com.coinflow.domain.rollup.domain.vo.OhlcInterval;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Table(name = "ohlc_rollup_checkpoint")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class RollupCheckpoint {

    @Id
    @Enumerated(EnumType.STRING)
    @Column(length = 16, nullable = false)
    private OhlcInterval interval;

    @Column
    private LocalDateTime lastBucketTime;

    @Column(nullable = false)
    private LocalDateTime updatedAt;

    public static RollupCheckpoint initial(OhlcInterval interval) {
        RollupCheckpoint cp = new RollupCheckpoint();
        cp.interval = interval;
        cp.lastBucketTime = null;
        return cp;
    }

    public static RollupCheckpoint from(OhlcInterval interval, LocalDateTime lastBucketTime) {
        if (interval == null) {
            throw new CoreException(ROLLUP_INVALID_INTERVAL);
        }

        if (lastBucketTime == null) {
            throw new CoreException(ROLLUP_TIME_RANGE_ERROR);
        }

        RollupCheckpoint cp = new RollupCheckpoint();
        cp.interval = interval;
        cp.lastBucketTime = lastBucketTime;
        return cp;
    }

    public void advanceTo(LocalDateTime bucketTime) {
        if (bucketTime == null) {
            throw new CoreException(ROLLUP_TIME_RANGE_ERROR);
        }

        if (this.lastBucketTime != null && bucketTime.isBefore(this.lastBucketTime)) {
            throw new CoreException(ROLLUP_CHECKPOINT_BACKWARD);
        }

        this.lastBucketTime = bucketTime;
    }

    @PrePersist
    protected void onPersist() {
        this.updatedAt = LocalDateTime.now();
    }

    @PreUpdate
    protected void onUpdate() {
        this.updatedAt = LocalDateTime.now();
    }
}
