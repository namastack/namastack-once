package io.namastack.once.query

/**
 * Implements a bounded PostgreSQL DELETE using a locking CTE in the same SQL statement.
 *
 * PostgreSQL has no direct DELETE LIMIT clause. The CTE locks eligible rows before deleting them,
 * avoiding a stale candidate list between competing cleanup workers. The DELETE joins the selected
 * markers by their identity primary key, which remains unique across partitions. Partition-local
 * physical row addresses could match unrelated markers and delete rows outside the selected batch.
 *
 * @param tableName Resolved marker table reference from trusted application configuration.
 *
 * @author Roland Beisel
 * @since 0.1.0
 */
internal class PostgresJdbcOnceMarkerQueries(
    tableName: String,
) : DefaultJdbcOnceMarkerQueries(tableName) {
    override val deleteExpiredBatch: String =
        """
        WITH expired_markers AS (
            SELECT idempotency_hash
            FROM $tableName
            WHERE expires_at <= :cutoff
            ORDER BY expires_at, idempotency_hash
            FOR UPDATE
            LIMIT :batchSize
        )
        DELETE FROM $tableName AS marker
        USING expired_markers
        WHERE marker.idempotency_hash = expired_markers.idempotency_hash
        """.trimIndent()
}
