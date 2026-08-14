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
VALUES (
    'btcusdt',
    'BINANCE',
    'Bitcoin / USDT',
    true,
    'SPOT',
    'btcusdt',
    NOW(),
    NOW()
)
ON CONFLICT (symbol) DO NOTHING;
