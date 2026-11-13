package io.namastack.once.query

/**
 * Uses Oracle's ROWNUM predicate to bound one atomic cleanup DELETE.
 *
 * @param tableName Resolved marker table reference from trusted application configuration.
 *
 * @author Roland Beisel
 * @since 0.1.0
 */
internal class OracleJdbcOnceMarkerQueries(
    tableName: String,
) : DefaultJdbcOnceMarkerQueries(tableName) {
    override val deleteExpiredBatch: String =
        """
        DELETE FROM $tableName
        WHERE expires_at <= :cutoff AND ROWNUM <= :batchSize
        """.trimIndent()
}
