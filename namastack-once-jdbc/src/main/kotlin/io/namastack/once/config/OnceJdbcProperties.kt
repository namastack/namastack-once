package io.namastack.once.config

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * Existing JDBC marker-table settings for the auto-configured default Once instance.
 *
 * Names are trusted SQL fragments used as configured, including any required identifier quoting.
 * The application owns schema creation and must preserve the supplied marker-table structure.
 *
 * @property tableName SQL name of the existing marker table; defaults to once_marker.
 * @property schemaName Optional schema or MySQL/MariaDB database name; null uses the connection's namespace.
 *
 * @author Roland Beisel
 * @since 0.1.0
 */
@ConfigurationProperties("namastack.once.jdbc", ignoreUnknownFields = false)
data class OnceJdbcProperties(
    var tableName: String = "once_marker",
    var schemaName: String? = null,
)
