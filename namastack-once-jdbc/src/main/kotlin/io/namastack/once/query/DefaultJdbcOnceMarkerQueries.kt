package io.namastack.once.query

/**
 * Provides the INSERT shared by all supported databases.
 *
 * Cleanup syntax is supplied by database-specific implementations so row limits are enforced by
 * the database rather than by selecting identities and deleting them individually in application code.
 *
 * @param tableName Resolved marker table reference from trusted application configuration.
 *
 * @author Roland Beisel
 * @since 0.1.0
 */
internal abstract class DefaultJdbcOnceMarkerQueries(
    protected val tableName: String,
) : JdbcOnceMarkerQueries {
    override val insertMarker: String =
        """
        INSERT INTO $tableName (idempotency_hash, scope, created_at, expires_at)
        VALUES (:keyHash, :scope, :createdAt, :expiresAt)
        """.trimIndent()
}
