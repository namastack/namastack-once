CREATE SCHEMA IF NOT EXISTS idempotency;

CREATE TABLE IF NOT EXISTS idempotency.processed_event (
    idempotency_hash CHAR(64) NOT NULL PRIMARY KEY,
    scope VARCHAR(200) NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    expires_at TIMESTAMP(6) NULL
);
CREATE INDEX IF NOT EXISTS ix_processed_event_expires_at ON idempotency.processed_event (expires_at);

CREATE TABLE IF NOT EXISTS demo_order (id VARCHAR(100) PRIMARY KEY);
