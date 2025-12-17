## Table

---
### Symbol

```sql
CREATE TABLE symbol (
    id BIGSERIAL PRIMARY KEY,
    symbol VARCHAR(20) NOT NULL,
    exchange VARCHAR(20) NOT NULL,
    name VARCHAR(100) NOT NULL,
    active BOOLEAN NOT NULL,
    market_type VARCHAR(20) NOT NULL,
    provider_symbol VARCHAR(50),
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL
);
```

### Tick

```sql
CREATE TABLE tick (
    id BIGSERIAL PRIMARY KEY,
    symbol_id BIGINT NOT NULL,
    price NUMERIC(18, 6) NOT NULL,
    volume BIGINT NOT NULL,
    tick_time TIMESTAMP NOT NULL,
    received_at TIMESTAMP NOT NULL,

    CONSTRAINT fk_tick_symbol
        FOREIGN KEY (symbol_id) REFERENCES symbol(id)
);
```

### ohlc_1m/5m/30m/1d

```sql
CREATE TABLE ohlc_1m/5m/30m/1d (
    id BIGSERIAL PRIMARY KEY,
    symbol_id BIGINT NOT NULL,
    bucket_time TIMESTAMP NOT NULL,
    open_price NUMERIC(18, 6) NOT NULL,
    high_price NUMERIC(18, 6) NOT NULL,
    low_price NUMERIC(18, 6) NOT NULL,
    close_price NUMERIC(18, 6) NOT NULL,
    volume BIGINT NOT NULL,
    updated_at TIMESTAMP NOT NULL,

    CONSTRAINT fk_ohlc_1m/5m/30m/1d_symbol
        FOREIGN KEY (symbol_id) REFERENCES symbol(id),

    CONSTRAINT uk_ohlc_1m/5m/30m/1d_symbol_bucket
        UNIQUE (symbol_id, bucket_time)
);
```

### Missing Tick Log
```sql
CREATE TABLE missing_tick_log (
    id BIGSERIAL PRIMARY KEY,
    symbol_id BIGINT NOT NULL,
    start_time TIMESTAMP NOT NULL,
    end_time TIMESTAMP NOT NULL,
    detected_at TIMESTAMP NOT NULL,
    resolved BOOLEAN NOT NULL DEFAULT FALSE,

    CONSTRAINT fk_missing_tick_symbol
        FOREIGN KEY (symbol_id) REFERENCES symbol(id)
);
```
