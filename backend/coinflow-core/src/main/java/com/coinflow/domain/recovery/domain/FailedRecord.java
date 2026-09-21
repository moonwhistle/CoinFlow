package com.coinflow.domain.recovery.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "failed_record")
@Getter @Setter @NoArgsConstructor
public class FailedRecord {
    public enum Status { WAITING_REPAIR, ACK_PENDING, RESOLVED }
    @Id @Column(length = 512) private String id;
    private String streamKey;
    private String consumerGroup;
    private String recordId;
    private String symbol;
    @Column(columnDefinition = "text") private String payload;
    @Column(columnDefinition = "text") private String requiredCandles;
    @Column(length = 2000) private String reason;
    private String dlqId;
    private boolean dlqExhausted;
    private int dlqAttempts;
    @Enumerated(EnumType.STRING) private Status status = Status.WAITING_REPAIR;
    private long createdAt;
    public static String key(String stream, String group, String record) {
        return stream + ":" + group + ":" + record;
    }
}
