package io.namastack.once.query

/**
 * Uses H2's native FETCH clause to bound one DELETE statement.
 *
 * The expiry predicate and row limit are evaluated by H2 in the same command.
 *
 * @param tableName Resolved marker table reference from trusted application configuration.
 *
 * @author Roland Beisel
 * @since 0.1.0
 */
internal class H2JdbcOnceMarkerQueries(
    tableName: String,
) : DefaultJdbcOnceMarkerQueries(tableName) {
    override val deleteExpiredBatch: String =
        """
        DELETE FROM $tableName
        WHERE expires_at <= :cutoff
        FETCH FIRST :batchSize ROWS ONLY
        """.trimIndent()
}
