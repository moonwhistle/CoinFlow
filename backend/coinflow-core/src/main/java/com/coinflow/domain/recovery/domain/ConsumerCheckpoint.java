package com.coinflow.domain.recovery.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** One fenced aggregation owner for the current single-stream deployment. */
@Entity
@Table(name = "consumer_checkpoint")
@Getter @Setter @NoArgsConstructor
public class ConsumerCheckpoint {
    @Id private Long id = 1L;
    private String streamKey;
    private String consumerGroup;
    private String recordId = "0-0";
    @Column(columnDefinition = "text") private String snapshot;
    private String owner;
    private long leaseUntil;
    @Version private Long version;
}
