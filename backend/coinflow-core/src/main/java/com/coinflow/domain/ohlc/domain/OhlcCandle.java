package com.coinflow.domain.ohlc.domain;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public interface OhlcCandle {

    LocalDateTime getBucketTime();

    BigDecimal getOpenPrice();

    BigDecimal getHighPrice();

    BigDecimal getLowPrice();

    BigDecimal getClosePrice();

    Long getVolume();
}
