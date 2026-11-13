package io.namastack.once.query

/**
 * Uses the native single-table DELETE limit shared by MySQL and MariaDB.
 *
 * Ordering eligible rows by expiry and identity gives concurrent cleanup workers a consistent
 * deletion order. No candidate identities are transferred to application code.
 *
 * @param tableName Resolved marker table reference from trusted application configuration.
 *
 * @author Roland Beisel
 * @since 0.1.0
 */
internal class MySqlJdbcOnceMarkerQueries(
    tableName: String,
) : DefaultJdbcOnceMarkerQueries(tableName) {
    override val deleteExpiredBatch: String =
        """
        DELETE FROM $tableName
        WHERE expires_at <= :cutoff
        ORDER BY expires_at, idempotency_hash
        LIMIT :batchSize
        """.trimIndent()
}
