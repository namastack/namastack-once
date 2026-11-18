-- Default Namastack Once marker table. Existing tables are not migrated.
IF OBJECT_ID('once_marker', 'U') IS NULL
CREATE TABLE once_marker (
    idempotency_hash CHAR(64) NOT NULL PRIMARY KEY,
    scope NVARCHAR(200) NOT NULL,
    created_at DATETIME2(6) NOT NULL,
    expires_at DATETIME2(6) NULL
);
IF NOT EXISTS (SELECT 1 FROM sys.indexes WHERE name = 'ix_once_marker_expires_at'
               AND object_id = OBJECT_ID('once_marker'))
CREATE INDEX ix_once_marker_expires_at ON once_marker (expires_at);
