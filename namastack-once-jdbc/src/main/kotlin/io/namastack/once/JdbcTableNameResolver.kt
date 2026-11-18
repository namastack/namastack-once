package io.namastack.once

/**
 * Resolves fully qualified table names for the JDBC once repositories.
 *
 * The default implementation ([DefaultJdbcTableNameResolver]) applies the configured schema name,
 * table prefix and base table names. Register a custom bean implementing this interface to take full
 * control over table naming (the auto-configuration registers the default behind
 * `@ConditionalOnMissingBean`).
 *
 * @author Roland Beisel
 * @since 0.2.0
 */
interface JdbcTableNameResolver {
    /**
     * Fully qualified table name for once records.
     */
    val onceMarker: String
}
