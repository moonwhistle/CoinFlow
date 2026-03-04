-- 테이블 생성
CREATE TABLE symbol (
    id BIGSERIAL PRIMARY KEY,
    symbol VARCHAR(20) NOT NULL,
    exchange VARCHAR(20) NOT NULL,
    name VARCHAR(100) NOT NULL,
    active BOOLEAN NOT NULL DEFAULT true,
    market_type VARCHAR(20) NOT NULL,
    provider_symbol VARCHAR(50),
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE TABLE ohlc1m (
    id BIGSERIAL PRIMARY KEY,
    symbol_id BIGINT NOT NULL,
    bucket_time TIMESTAMP NOT NULL,
    open_price NUMERIC NOT NULL,
    high_price NUMERIC NOT NULL,
    low_price NUMERIC NOT NULL,
    close_price NUMERIC NOT NULL,
    volume BIGINT NOT NULL
);

CREATE TABLE ohlc5m (
    id BIGSERIAL PRIMARY KEY,
    symbol_id BIGINT NOT NULL,
    bucket_time TIMESTAMP NOT NULL,
    open_price NUMERIC NOT NULL,
    high_price NUMERIC NOT NULL,
    low_price NUMERIC NOT NULL,
    close_price NUMERIC NOT NULL,
    volume BIGINT NOT NULL
);

CREATE TABLE ohlc30m (
    id BIGSERIAL PRIMARY KEY,
    symbol_id BIGINT NOT NULL,
    bucket_time TIMESTAMP NOT NULL,
    open_price NUMERIC NOT NULL,
    high_price NUMERIC NOT NULL,
    low_price NUMERIC NOT NULL,
    close_price NUMERIC NOT NULL,
    volume BIGINT NOT NULL
);

-- Symbol 초기 데이터 (BTCUSDT)
INSERT INTO symbol (symbol, exchange, name, active, market_type, provider_symbol)
VALUES ('btcusdt', 'BINANCE', 'Bitcoin / USDT', true, 'SPOT', 'btcusdt');

\q
