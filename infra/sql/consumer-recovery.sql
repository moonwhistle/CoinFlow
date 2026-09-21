-- PostgreSQL: apply before deploying consumer/replay when Hibernate schema update is disabled.
-- Does not reset existing checkpoints, PEL, candles or consumer groups.
CREATE TABLE IF NOT EXISTS consumer_checkpoint (
    id BIGINT PRIMARY KEY,
    stream_key VARCHAR(255), consumer_group VARCHAR(255), record_id VARCHAR(255),
    snapshot TEXT, owner VARCHAR(255), lease_until BIGINT NOT NULL, version BIGINT
);
CREATE TABLE IF NOT EXISTS failed_record (
    id VARCHAR(512) PRIMARY KEY,
    stream_key VARCHAR(255), consumer_group VARCHAR(255), record_id VARCHAR(255), symbol VARCHAR(255),
    payload TEXT, required_candles TEXT, reason VARCHAR(2000), dlq_id VARCHAR(255),
    dlq_exhausted BOOLEAN NOT NULL, dlq_attempts INTEGER NOT NULL,
    status VARCHAR(255), created_at BIGINT NOT NULL
);
CREATE TABLE IF NOT EXISTS verified_candle (
    id VARCHAR(180) PRIMARY KEY,
    symbol VARCHAR(255), interval_name VARCHAR(255), bucket BIGINT NOT NULL,
    verified_at BIGINT NOT NULL, cache_published BOOLEAN NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_failed_record_unresolved ON failed_record(id) WHERE status <> 'RESOLVED';
CREATE INDEX IF NOT EXISTS idx_verified_candle_cache ON verified_candle(cache_published, verified_at);
INSERT INTO consumer_checkpoint(id, record_id, lease_until, version)
VALUES (1, '0-0', 0, 0) ON CONFLICT (id) DO NOTHING;
