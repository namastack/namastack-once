package io.namastack.once.config

import io.namastack.once.DefaultIdempotencyOperations
import io.namastack.once.IdempotencyKeyNormalizer
import io.namastack.once.IdempotencyOperations
import io.namastack.once.IdempotencyRequest
import io.namastack.once.JdbcIdempotencyMarkerRepository
import io.namastack.once.JdbcTableNameResolver
import io.namastack.once.query.JdbcOnceMarkerQueriesFactory
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.beans.factory.getBean
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration
import org.springframework.boot.jdbc.autoconfigure.DataSourceTransactionManagerAutoConfiguration
import org.springframework.boot.jdbc.init.DataSourceScriptDatabaseInitializer
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.queryForObject
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.springframework.jdbc.support.JdbcTransactionManager
import java.time.Clock
import java.util.UUID
import javax.sql.DataSource

class JdbcOnceSchemaAutoConfigurationTest {
    private val dataSource = DriverManagerDataSource("jdbc:h2:mem:${UUID.randomUUID()};DB_CLOSE_DELAY=-1", "sa", "")
    private val jdbc = JdbcTemplate(dataSource)
    private val transactionManager = JdbcTransactionManager(dataSource)
    private val contextRunner =
        ApplicationContextRunner()
            .withConfiguration(
                AutoConfigurations.of(
                    OnceCoreInfrastructureAutoConfiguration::class.java,
                    JdbcOnceAutoConfiguration::class.java,
                    JdbcOnceSchemaAutoConfiguration::class.java,
                ),
            )
    private val runner =
        contextRunner
            .withBean(DataSource::class.java, { dataSource })
            .withBean(JdbcTransactionManager::class.java, { transactionManager })

    @Test
    fun `backs off when no datasource is configured`() {
        contextRunner.run { context ->
            assertThat(context).hasNotFailed()
            assertThat(context).doesNotHaveBean(DataSourceScriptDatabaseInitializer::class.java)
            assertThat(context).doesNotHaveBean(IdempotencyOperations::class.java)
        }
    }

    @Test
    fun `initializes a datasource supplied by Spring Boot auto-configuration`() {
        contextRunner
            .withConfiguration(
                AutoConfigurations.of(
                    DataSourceAutoConfiguration::class.java,
                    DataSourceTransactionManagerAutoConfiguration::class.java,
                ),
            ).withPropertyValues("spring.datasource.url=jdbc:h2:mem:${UUID.randomUUID()};DB_CLOSE_DELAY=-1")
            .run { context ->
                assertThat(context).hasNotFailed()
                assertThat(context).hasSingleBean(DataSourceScriptDatabaseInitializer::class.java)
                val jdbc = JdbcTemplate(context.getBean<DataSource>())
                assertThat(jdbc.queryForObject<Int>("SELECT COUNT(*) FROM once_marker")).isZero()
            }
    }

    @Test
    fun `an explicitly configured Once instance owns its schema initialization`() {
        createMarkerTable("managed_marker")
        val repository =
            JdbcIdempotencyMarkerRepository(dataSource, transactionManager) {
                JdbcOnceMarkerQueriesFactory.create(JdbcDatabaseType.H2, "managed_marker")
            }
        val once =
            DefaultIdempotencyOperations(
                transactionManager,
                repository,
                IdempotencyKeyNormalizer(),
                Clock.systemUTC(),
            )

        runner.withBean(IdempotencyOperations::class.java, { once }).run { context ->
            assertThat(context).hasNotFailed()
            assertThat(context).doesNotHaveBean(DataSourceScriptDatabaseInitializer::class.java)
            assertThat(context.getBean<IdempotencyOperations>()).isSameAs(once)
            val request = IdempotencyRequest.of("explicit-instance", "key")
            assertThat(once.executeWithoutResult(request) { }.wasExecuted()).isTrue()
            assertThat(once.executeWithoutResult(request) { error("duplicate must skip") }.wasExecuted()).isFalse()
            assertThat(markerRows("managed_marker")).isEqualTo(1)
            assertThat(markerTableCount()).isZero()
        }
    }

    @Test
    fun `creates the marker table and expiry index at startup`() {
        runner.run { context ->
            assertThat(context).hasNotFailed()
            assertThat(context).hasSingleBean(DataSourceScriptDatabaseInitializer::class.java)
            assertThat(markerRows("once_marker")).isZero()
            assertThat(
                jdbc.queryForObject<Int>(
                    "SELECT COUNT(*) FROM INFORMATION_SCHEMA.INDEXES WHERE INDEX_NAME = 'IX_ONCE_MARKER_EXPIRES_AT'",
                ),
            ).isEqualTo(1)
        }
    }

    @Test
    fun `restarting the application preserves existing markers`() {
        val request = IdempotencyRequest.of("test", "key")
        runner.run { context ->
            assertThat(context).hasNotFailed()
            val once = context.getBean<IdempotencyOperations>()
            assertThat(once.executeWithoutResult(request) { }.wasExecuted()).isTrue()
        }
        runner.run { context ->
            assertThat(context).hasNotFailed()
            val once = context.getBean<IdempotencyOperations>()
            assertThat(once.executeWithoutResult(request) { error("duplicate must skip") }.wasExecuted()).isFalse()
            assertThat(markerRows("once_marker")).isEqualTo(1)
        }
    }

    @Test
    fun `disabled initialization creates no tables`() {
        runner.withPropertyValues("namastack.once.jdbc.schema-initialization.enabled=false").run { context ->
            assertThat(context).hasNotFailed()
            assertThat(context).doesNotHaveBean(DataSourceScriptDatabaseInitializer::class.java)
            assertThat(markerTableCount()).isZero()
        }
    }

    @Test
    fun `disabled Once creates no tables`() {
        runner.withPropertyValues("namastack.once.enabled=false").run { context ->
            assertThat(context).hasNotFailed()
            assertThat(context).doesNotHaveBean(DataSourceScriptDatabaseInitializer::class.java)
            assertThat(markerTableCount()).isZero()
        }
    }

    @Test
    fun `existing table-name configuration preserves markers across restarts`() {
        createMarkerTable("existing_marker")
        val request = IdempotencyRequest.of("upgrade", "existing-key")
        val customRunner =
            runner.withPropertyValues(
                "namastack.once.jdbc.table-name=existing_marker",
                "namastack.once.jdbc.schema-initialization.enabled=false",
            )

        customRunner.run { context ->
            assertThat(context).hasNotFailed()
            val once = context.getBean<IdempotencyOperations>()
            assertThat(once.executeWithoutResult(request) { }.wasExecuted()).isTrue()
        }

        customRunner.run { context ->
            assertThat(context).hasNotFailed()
            val once = context.getBean<IdempotencyOperations>()
            assertThat(
                once.executeWithoutResult(request) { error("existing marker must skip") }.wasExecuted(),
            ).isFalse()
            assertThat(markerTableCount()).isZero()
            assertThat(markerRows("existing_marker")).isEqualTo(1)
        }
    }

    @ParameterizedTest(name = "rejects {0} when automatic initialization is enabled")
    @ValueSource(strings = ["schema-name=custom", "table-name=custom_marker", "table-prefix=custom_"])
    fun `custom naming requires manual schema initialization`(setting: String) {
        runner.withPropertyValues("namastack.once.jdbc.$setting").run { context ->
            assertThat(context).hasFailed()
            assertThat(context.startupFailure)
                .hasRootCauseInstanceOf(IllegalStateException::class.java)
                .hasStackTraceContaining("disable schema initialization")
            assertThat(markerTableCount()).isZero()
        }
    }

    @Test
    fun `custom schema and quoted table work with manually managed schema`() {
        jdbc.execute("CREATE SCHEMA custom")
        createMarkerTable("custom.\"custom marker\"")
        runner
            .withPropertyValues(
                "namastack.once.jdbc.schema-initialization.enabled=false",
                "namastack.once.jdbc.schema-name=custom",
                "namastack.once.jdbc.table-name=\"custom marker\"",
            ).run { context ->
                assertThat(context).hasNotFailed()
                val once = context.getBean<IdempotencyOperations>()
                val request = IdempotencyRequest.of("custom", "key")
                once.executeWithoutResult(request) { }
                assertThat(once.executeWithoutResult(request) { error("duplicate must skip") }.wasExecuted()).isFalse()
                assertThat(markerRows("custom.\"custom marker\"")).isEqualTo(1)
                assertThat(markerTableCount()).isZero()
            }
    }

    @Test
    fun `configured schema prefix and base table name are used by the repository`() {
        jdbc.execute("CREATE SCHEMA custom")
        createMarkerTable("custom.app_markers")
        runner
            .withPropertyValues(
                "namastack.once.jdbc.schema-initialization.enabled=false",
                "namastack.once.jdbc.schema-name=custom",
                "namastack.once.jdbc.table-prefix=app_",
                "namastack.once.jdbc.table-name=markers",
            ).run { context ->
                assertThat(context).hasNotFailed()
                val resolver = context.getBean<JdbcTableNameResolver>()
                val once = context.getBean<IdempotencyOperations>()

                assertThat(resolver.onceMarker).isEqualTo("custom.app_markers")
                once.executeWithoutResult(IdempotencyRequest.of("prefix", "key")) { }
                assertThat(markerRows("custom.app_markers")).isEqualTo(1)
            }
    }

    @Test
    fun `a custom resolver replaces the default and supplies repository table names`() {
        createMarkerTable("custom_markers")
        val resolver =
            object : JdbcTableNameResolver {
                override val onceMarker = "custom_markers"
            }
        runner
            .withPropertyValues("namastack.once.jdbc.schema-initialization.enabled=false")
            .withBean(JdbcTableNameResolver::class.java, { resolver })
            .run { context ->
                assertThat(context).hasNotFailed()
                assertThat(context).hasSingleBean(JdbcTableNameResolver::class.java)
                assertThat(context.getBean<JdbcTableNameResolver>()).isSameAs(resolver)
                val once = context.getBean<IdempotencyOperations>()
                once.executeWithoutResult(IdempotencyRequest.of("resolver", "key")) { }
                assertThat(markerRows("custom_markers")).isEqualTo(1)
            }
    }

    private fun createMarkerTable(tableName: String) {
        jdbc.execute(
            """
            CREATE TABLE $tableName (
                idempotency_hash CHAR(64) PRIMARY KEY,
                scope VARCHAR(200) NOT NULL,
                created_at TIMESTAMP(6) NOT NULL,
                expires_at TIMESTAMP(6)
            )
            """.trimIndent(),
        )
    }

    private fun markerRows(tableName: String): Int =
        checkNotNull(jdbc.queryForObject<Int>("SELECT COUNT(*) FROM $tableName"))

    private fun markerTableCount(): Int =
        checkNotNull(
            jdbc.queryForObject<Int>("SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_NAME = 'ONCE_MARKER'"),
        )
}
