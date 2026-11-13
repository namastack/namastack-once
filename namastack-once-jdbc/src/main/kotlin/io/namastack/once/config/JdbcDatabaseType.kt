package io.namastack.once.config

import org.springframework.jdbc.support.JdbcUtils
import java.sql.DatabaseMetaData
import javax.sql.DataSource

/**
 * Supported SQL dialects for JDBC marker reservation and cleanup.
 *
 * Detection uses Spring's transaction-aware database metadata access.
 *
 * @author Roland Beisel
 * @since 0.1.0
 */
enum class JdbcDatabaseType {
    /** PostgreSQL statements using a locking CTE for bounded cleanup. */
    POSTGRESQL,

    /** MySQL statements using a native DELETE limit. */
    MYSQL,

    /** MariaDB statements sharing MySQL's native DELETE limit. */
    MARIADB,

    /** Oracle statements using ROWNUM to bound cleanup. */
    ORACLE,

    /** SQL Server statements using DELETE TOP to bound cleanup. */
    SQLSERVER,

    /** H2 statements using FETCH to bound cleanup. */
    H2,
    ;

    companion object {
        /**
         * Maps a supported database name to its SQL dialect, ignoring case.
         *
         * @param databaseName Supported JDBC database product name.
         * @return The supported SQL dialect.
         * @throws IllegalArgumentException if the database name is unsupported.
         */
        @JvmStatic
        fun from(databaseName: String): JdbcDatabaseType =
            when (databaseName.lowercase()) {
                "postgresql" -> POSTGRESQL
                "mysql" -> MYSQL
                "mariadb" -> MARIADB
                "h2" -> H2
                "oracle" -> ORACLE
                "microsoft sql server" -> SQLSERVER
                else -> throw IllegalArgumentException("Unsupported Once JDBC database: $databaseName")
            }

        /**
         * Reads the database product name using a connection obtained through Spring.
         *
         * @param dataSource Datasource whose database metadata identifies the dialect.
         * @return The supported SQL dialect.
         * @throws org.springframework.jdbc.support.MetaDataAccessException if database metadata cannot be read.
         * @throws IllegalStateException if the metadata does not provide a database name.
         * @throws IllegalArgumentException if the database product is unsupported.
         */
        @JvmStatic
        fun detect(dataSource: DataSource): JdbcDatabaseType {
            val productName = JdbcUtils.extractDatabaseMetaData(dataSource, DatabaseMetaData::getDatabaseProductName)
            val databaseName =
                checkNotNull(JdbcUtils.commonDatabaseName(productName)) { "Could not detect database name" }
            return from(databaseName)
        }
    }
}
