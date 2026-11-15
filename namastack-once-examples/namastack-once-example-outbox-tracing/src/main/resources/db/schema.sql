CREATE TABLE once_marker (
    idempotency_hash CHAR(64) NOT NULL PRIMARY KEY,
    scope VARCHAR(200) NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    expires_at TIMESTAMP(6) NULL
);
CREATE INDEX ix_once_marker_expires_at ON once_marker (expires_at);

CREATE TABLE demo_order (id VARCHAR(200) PRIMARY KEY);
CREATE TABLE demo_invoice (event_id VARCHAR(200) PRIMARY KEY);
