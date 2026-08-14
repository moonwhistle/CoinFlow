SELECT pg_advisory_lock(hashtext('coinflow:symbol:btcusdt'));

INSERT INTO symbol (
    symbol,
    exchange,
    name,
    active,
    market_type,
    provider_symbol,
    created_at,
    updated_at
)
SELECT
    'btcusdt',
    'BINANCE',
    'Bitcoin / USDT',
    true,
    'SPOT',
    'btcusdt',
    NOW(),
    NOW()
WHERE NOT EXISTS (
    SELECT 1
    FROM symbol
    WHERE symbol = 'btcusdt'
);

SELECT pg_advisory_unlock(hashtext('coinflow:symbol:btcusdt'));
