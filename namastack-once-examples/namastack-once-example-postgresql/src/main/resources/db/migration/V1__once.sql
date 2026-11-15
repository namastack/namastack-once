CREATE TABLE IF NOT EXISTS once_marker (
    idempotency_hash CHAR(64) NOT NULL PRIMARY KEY,
    scope VARCHAR(200) NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    expires_at TIMESTAMP(6) NULL
);
CREATE INDEX IF NOT EXISTS ix_once_marker_expires_at ON once_marker (expires_at, idempotency_hash);
