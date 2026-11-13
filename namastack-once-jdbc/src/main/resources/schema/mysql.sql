-- Application-managed migration for Namastack Once 0.1.0 (mysql).
-- Once never runs this script automatically.
CREATE TABLE once_marker (
    idempotency_hash CHAR(64) NOT NULL PRIMARY KEY,
    scope VARCHAR(200) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    expires_at DATETIME(6) NULL
) ENGINE = InnoDB;
CREATE INDEX ix_once_marker_expires_at ON once_marker (expires_at);
