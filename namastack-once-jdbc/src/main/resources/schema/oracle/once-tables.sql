-- Default Namastack Once marker table. Existing tables are not migrated.
DECLARE
    v_count NUMBER;
BEGIN
    SELECT COUNT(*) INTO v_count FROM user_tables WHERE table_name = 'ONCE_MARKER';
    IF v_count = 0 THEN
        EXECUTE IMMEDIATE '
            CREATE TABLE once_marker (
                idempotency_hash CHAR(64) NOT NULL PRIMARY KEY,
                scope VARCHAR2(200 CHAR) NOT NULL,
                created_at TIMESTAMP(6) NOT NULL,
                expires_at TIMESTAMP(6) NULL
            )';
        EXECUTE IMMEDIATE 'CREATE INDEX ix_once_marker_expires_at ON once_marker (expires_at)';
    END IF;
END;
/
