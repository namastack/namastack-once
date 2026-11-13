package io.namastack.once.query

/**
 * Uses SQL Server's TOP clause to bound one atomic cleanup DELETE.
 *
 * @param tableName Resolved marker table reference from trusted application configuration.
 *
 * @author Roland Beisel
 * @since 0.1.0
 */
internal class SqlServerJdbcOnceMarkerQueries(
    tableName: String,
) : DefaultJdbcOnceMarkerQueries(tableName) {
    override val deleteExpiredBatch: String =
        """
        DELETE TOP (:batchSize) FROM $tableName
        WHERE expires_at <= :cutoff
        """.trimIndent()
}
