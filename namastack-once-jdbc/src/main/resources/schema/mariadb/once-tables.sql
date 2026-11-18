-- Default Namastack Once marker table. Existing tables are not migrated.
CREATE TABLE IF NOT EXISTS once_marker (
    idempotency_hash CHAR(64) NOT NULL PRIMARY KEY,
    scope VARCHAR(200) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    expires_at DATETIME(6) NULL,
    INDEX ix_once_marker_expires_at (expires_at)
) ENGINE = InnoDB;
