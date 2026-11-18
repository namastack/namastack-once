package io.namastack.once

import io.namastack.once.config.JdbcOnceConfigurationProperties

/**
 * Default [JdbcTableNameResolver] implementation.
 *
 * Applies the schema name and table prefix to the configured base table names, producing fully
 * qualified table names for use in SQL queries. It can be constructed from plain namespace values
 * for programmatic runtimes or from [JdbcOnceConfigurationProperties] for auto-configuration.
 *
 * @param schemaName Optional database schema containing the once tables
 * @param tablePrefix Prefix applied to every once table name
 * @param markerTableName Base table name for once records
 *
 * @author Roland Beisel
 * @since 0.2.0
 */
class DefaultJdbcTableNameResolver(
    schemaName: String? = null,
    tablePrefix: String = "",
    markerTableName: String = "once_marker",
) : JdbcTableNameResolver {
    /**
     * Creates the resolver from the properties used by JDBC auto-configuration.
     *
     * @param properties JDBC once configuration properties
     */
    constructor(properties: JdbcOnceConfigurationProperties) :
        this(
            schemaName = properties.schemaName,
            tablePrefix = properties.tablePrefix,
            markerTableName = properties.tableName,
        )

    override val onceMarker: String = resolve(schemaName, tablePrefix, markerTableName)

    private fun resolve(
        schemaName: String?,
        tablePrefix: String,
        baseTableName: String,
    ): String {
        val tableName = "$tablePrefix$baseTableName"
        return schemaName?.let { "$it.$tableName" } ?: tableName
    }
}
