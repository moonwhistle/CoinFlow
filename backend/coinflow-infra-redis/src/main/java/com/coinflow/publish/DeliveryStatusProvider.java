package com.coinflow.publish;

import java.util.Map;

public interface DeliveryStatusProvider {
    boolean isHealthy();
    Map<String, Object> details();
}
