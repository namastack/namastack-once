package io.namastack.once.config

import io.namastack.once.IdempotencyOperations
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.jdbc.init.DataSourceScriptDatabaseInitializer
import org.springframework.boot.sql.init.DatabaseInitializationMode
import org.springframework.boot.sql.init.DatabaseInitializationSettings
import org.springframework.context.annotation.Bean
import org.springframework.jdbc.core.simple.JdbcClient
import javax.sql.DataSource

/**
 * Auto-configuration for JDBC once database schema initialization.
 *
 * This configuration handles database schema creation when enabled.
 * Requires a DataSource to be present and applies only to the auto-configured default Once instance.
 *
 * @author Roland Beisel, Khalid Alharisi
 * @since 0.2.0
 */
@AutoConfiguration(
    before = [JdbcOnceAutoConfiguration::class],
    afterName = ["org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration"],
)
@ConditionalOnClass(DataSource::class, JdbcClient::class)
@ConditionalOnBean(DataSource::class)
@ConditionalOnMissingBean(IdempotencyOperations::class)
@EnableConfigurationProperties(JdbcOnceConfigurationProperties::class)
@ConditionalOnProperty(name = ["namastack.once.enabled"], havingValue = "true", matchIfMissing = true)
class JdbcOnceSchemaAutoConfiguration {
    /**
     * Creates a database initializer for once schema when schema initialization is enabled.
     *
     * This bean is created by default unless the property 'once.jdbc.schema-initialization.enabled' is set to false.
     *
     * @param dataSource The data source to initialize
     * @return Database initializer for once schema
     */
    @Bean
    @ConditionalOnProperty(
        name = ["namastack.once.jdbc.schema-initialization.enabled"],
        havingValue = "true",
        matchIfMissing = true,
    )
    internal fun onceDataSourceScriptDatabaseInitializer(
        dataSource: DataSource,
        properties: JdbcOnceConfigurationProperties,
    ): DataSourceScriptDatabaseInitializer {
        validateNoCustomTableNaming(properties)

        val databaseType = JdbcDatabaseType.from(dataSource)

        val settings = DatabaseInitializationSettings()
        settings.schemaLocations = mutableListOf(databaseType.schemaLocation)
        settings.separator = databaseType.statementSeparator
        settings.mode = DatabaseInitializationMode.ALWAYS

        return DataSourceScriptDatabaseInitializer(dataSource, settings)
    }

    private fun validateNoCustomTableNaming(properties: JdbcOnceConfigurationProperties) {
        val hasSchemaName = !properties.schemaName.isNullOrEmpty()
        val hasTablePrefix = properties.tablePrefix.isNotEmpty()
        val defaults = JdbcOnceConfigurationProperties()
        val hasCustomTableName =
            properties.tableName != defaults.tableName

        if (hasTablePrefix || hasSchemaName || hasCustomTableName) {
            throw IllegalStateException(
                "Cannot use automatic schema initialization " +
                    "(namastack.once.jdbc.schema-initialization.enabled=true) together with custom schema name, " +
                    "table prefix or table names. Either disable schema initialization and create tables manually " +
                    "with your desired naming, or remove the schema-name, table-prefix and table-name configuration.",
            )
        }
    }
}
