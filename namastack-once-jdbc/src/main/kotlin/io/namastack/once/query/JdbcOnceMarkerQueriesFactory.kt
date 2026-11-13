package io.namastack.once.query

import io.namastack.once.config.JdbcDatabaseType

/**
 * Selects marker statements for a database type supplied by the caller.
 *
 * Creating statements opens no connection. MySQL and MariaDB share their native bounded DELETE syntax;
 * other databases supply their own row-limit implementations.
 *
 * @author Roland Beisel
 * @since 0.1.0
 */
object JdbcOnceMarkerQueriesFactory {
    /**
     * Creates the statements for a supported database and resolved table name.
     *
     * @param database Supported database product.
     * @param tableName Trusted SQL table reference, including any required schema qualification and quoting.
     * @return INSERT and atomic bounded DELETE statements for the selected database.
     */
    @JvmStatic
    fun create(
        database: JdbcDatabaseType,
        tableName: String,
    ): JdbcOnceMarkerQueries =
        when (database) {
            JdbcDatabaseType.POSTGRESQL -> PostgresJdbcOnceMarkerQueries(tableName)
            JdbcDatabaseType.MYSQL, JdbcDatabaseType.MARIADB -> MySqlJdbcOnceMarkerQueries(tableName)
            JdbcDatabaseType.H2 -> H2JdbcOnceMarkerQueries(tableName)
            JdbcDatabaseType.ORACLE -> OracleJdbcOnceMarkerQueries(tableName)
            JdbcDatabaseType.SQLSERVER -> SqlServerJdbcOnceMarkerQueries(tableName)
        }
}
