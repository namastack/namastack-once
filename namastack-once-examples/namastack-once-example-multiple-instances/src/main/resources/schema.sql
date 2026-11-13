-- Application-managed migration for Namastack Once 0.1.0 (h2).
-- Once never runs this script automatically.
CREATE TABLE IF NOT EXISTS orders_once (
    idempotency_hash CHAR(64) NOT NULL PRIMARY KEY,
    scope VARCHAR(200) NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    expires_at TIMESTAMP(6) NULL
);
CREATE INDEX IF NOT EXISTS ix_orders_once_expires_at ON orders_once (expires_at);

-- Application-managed migration for Namastack Once 0.1.0 (h2).
-- Once never runs this script automatically.
CREATE TABLE IF NOT EXISTS billing_once (
    idempotency_hash CHAR(64) NOT NULL PRIMARY KEY,
    scope VARCHAR(200) NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    expires_at TIMESTAMP(6) NULL
);
CREATE INDEX IF NOT EXISTS ix_billing_once_expires_at ON billing_once (expires_at);

CREATE TABLE demo_effect (category VARCHAR(20) NOT NULL, event_id VARCHAR(100) NOT NULL, PRIMARY KEY (category, event_id));
