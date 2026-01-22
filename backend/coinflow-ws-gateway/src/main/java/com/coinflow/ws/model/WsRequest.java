package com.coinflow.ws.model;

import java.util.List;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

@Getter
@Setter
@ToString
@NoArgsConstructor
public class WsRequest {

    private WsCommandType type;
    private List<WsSubscription> topics;

    @Getter
    @Setter
    @ToString
    @NoArgsConstructor
    public static class WsSubscription {
        private String symbol;
        private String interval; // Optional, placeholder for now
    }
}
