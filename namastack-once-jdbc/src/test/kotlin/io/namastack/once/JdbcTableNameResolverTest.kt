package io.namastack.once

import io.namastack.once.config.JdbcOnceConfigurationProperties
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class JdbcTableNameResolverTest {
    @Nested
    inner class DefaultResolution {
        @Test
        fun `return base table name when no prefix and no schema configured`() {
            val resolver = DefaultJdbcTableNameResolver(JdbcOnceConfigurationProperties())

            assertThat(resolver.onceMarker).isEqualTo("once_marker")
        }

        @Test
        fun `apply table prefix when configured`() {
            val properties = JdbcOnceConfigurationProperties(tablePrefix = "my_")
            val resolver = DefaultJdbcTableNameResolver(properties)

            assertThat(resolver.onceMarker).isEqualTo("my_once_marker")
        }

        @Test
        fun `apply schema name when configured`() {
            val properties = JdbcOnceConfigurationProperties(schemaName = "custom_schema")
            val resolver = DefaultJdbcTableNameResolver(properties)

            assertThat(resolver.onceMarker).isEqualTo("custom_schema.once_marker")
        }

        @Test
        fun `apply both schema and prefix when configured`() {
            val properties =
                JdbcOnceConfigurationProperties(
                    tablePrefix = "app_",
                    schemaName = "myschema",
                )
            val resolver = DefaultJdbcTableNameResolver(properties)

            assertThat(resolver.onceMarker).isEqualTo("myschema.app_once_marker")
        }
    }

    @Nested
    inner class CustomTableNames {
        @Test
        fun `use configured base table names`() {
            val properties =
                JdbcOnceConfigurationProperties(
                    tableName = "ONCE_MARKER",
                )
            val resolver = DefaultJdbcTableNameResolver(properties)

            assertThat(resolver.onceMarker).isEqualTo("ONCE_MARKER")
        }

        @Test
        fun `combine prefix and schema with custom base names`() {
            val properties =
                JdbcOnceConfigurationProperties(
                    tablePrefix = "ACME_",
                    schemaName = "APP",
                    tableName = "ONCE_MARKER",
                )
            val resolver = DefaultJdbcTableNameResolver(properties)

            assertThat(resolver.onceMarker).isEqualTo("APP.ACME_ONCE_MARKER")
        }
    }

    @Nested
    inner class ProgrammaticResolution {
        @Test
        fun `resolve a complete namespace`() {
            val namespace =
                DefaultJdbcTableNameResolver(
                    schemaName = "once_schema",
                    tablePrefix = "orders_",
                    markerTableName = "records",
                )

            assertThat(namespace.onceMarker).isEqualTo("once_schema.orders_records")
        }

        @Test
        fun `preserve database-specific identifier syntax`() {
            val quoted =
                DefaultJdbcTableNameResolver(
                    schemaName = "\"OnceSchema\"",
                    markerTableName = "\"OnceMarker\"",
                )
            val sqlServer =
                DefaultJdbcTableNameResolver(
                    schemaName = "[database].[schema]",
                    markerTableName = "[once_marker]",
                )

            assertThat(quoted.onceMarker).isEqualTo("\"OnceSchema\".\"OnceMarker\"")
            assertThat(sqlServer.onceMarker).isEqualTo("[database].[schema].[once_marker]")
        }
    }
}
