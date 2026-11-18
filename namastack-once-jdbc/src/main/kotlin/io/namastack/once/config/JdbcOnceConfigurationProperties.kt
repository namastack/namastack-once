package io.namastack.once.config

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * Configuration properties for the JDBC once module.
 *
 * @property schemaName Database schema name for once tables (e.g., "myschema" results in "myschema.once_marker").
 *                      Defaults to null (uses default schema).
 * @property tablePrefix Prefix to apply to all once table names (e.g., "my_" results in "my_once_marker").
 *                       Defaults to empty string (no prefix).
 * @property tableName Base marker table name. Defaults to "once_marker".
 * @property schemaInitialization Configuration for automatic schema initialization.
 *
 * @author Roland Beisel
 * @since 0.2.0
 */
@ConfigurationProperties(prefix = "namastack.once.jdbc", ignoreUnknownFields = false)
data class JdbcOnceConfigurationProperties(
    var schemaName: String? = null,
    var tablePrefix: String = "",
    var tableName: String = "once_marker",
    var schemaInitialization: SchemaInitialization = SchemaInitialization(),
) {
    /**
     * Configuration for automatic database schema initialization.
     *
     * Note: Schema initialization cannot be used together with custom table prefixes, schema names or
     * custom table names. When using custom naming, create tables manually using Flyway, Liquibase, or
     * plain SQL scripts.
     *
     * @property enabled Whether to automatically create once tables at startup. Defaults to true.
     */
    data class SchemaInitialization(
        var enabled: Boolean = true,
    )
}
