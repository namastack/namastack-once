package io.namastack.once

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.NoUniqueBeanDefinitionException
import org.springframework.beans.factory.getBean
import org.springframework.beans.factory.getBeansOfType
import org.springframework.dao.DataAccessException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.queryForObject
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.springframework.jdbc.support.JdbcTransactionManager
import java.time.Clock
import java.util.UUID
import java.util.function.Supplier
import javax.sql.DataSource

class JdbcOnceAutoConfigurationIntegrationTest {
    private val runner = multipleOnceTestApplicationContext()

    @Test
    fun `default creation requires an unambiguous transaction manager`() {
        val dataSource = createTestMarkerDataSource("once_marker")
        runner
            .withBean(DataSource::class.java, Supplier { dataSource })
            .withBean(
                "firstManager",
                JdbcTransactionManager::class.java,
                Supplier { JdbcTransactionManager(dataSource) },
            ).withBean(
                "secondManager",
                JdbcTransactionManager::class.java,
                Supplier { JdbcTransactionManager(dataSource) },
            ).run { context ->
                assertThat(context).hasFailed()
                assertThat(context.startupFailure).hasRootCauseInstanceOf(NoUniqueBeanDefinitionException::class.java)
            }
    }

    @Test
    fun `default creation requires an unambiguous datasource`() {
        val ordersDataSource = createTestMarkerDataSource("once_marker")
        val billingDataSource = createTestMarkerDataSource("once_marker")
        runner
            .withBean("ordersDataSource", DataSource::class.java, Supplier { ordersDataSource })
            .withBean("billingDataSource", DataSource::class.java, Supplier { billingDataSource })
            .withBean(JdbcTransactionManager::class.java, Supplier { JdbcTransactionManager(ordersDataSource) })
            .run { context ->
                assertThat(context).hasFailed()
                assertThat(context.startupFailure).hasRootCauseInstanceOf(NoUniqueBeanDefinitionException::class.java)
            }
    }

    @Test
    fun `missing schema fails on access without creating tables or invoking business work`() {
        val dataSource = DriverManagerDataSource("jdbc:h2:mem:missing_${UUID.randomUUID()};DB_CLOSE_DELAY=-1", "sa", "")
        val manager = JdbcTransactionManager(dataSource)
        val repository = createTestRepository(dataSource, manager)
        val once =
            DefaultIdempotencyOperations(
                manager,
                repository,
                IdempotencyKeyNormalizer(),
                Clock.systemUTC(),
            )
        var actionExecuted = false
        assertThat(countTables(dataSource)).isZero()
        assertThatThrownBy {
            once.executeWithoutResult(IdempotencyRequest.of("missing-schema", "key")) {
                actionExecuted = true
            }
        }.isInstanceOf(DataAccessException::class.java)
        assertThat(actionExecuted).isFalse()
        assertThat(countTables(dataSource)).isZero()
    }

    @Test
    fun `default wiring uses trusted configured table names without identifier validation`() {
        onceTestApplicationContext()
            .withPropertyValues("namastack.once.jdbc.table-name=\"custom marker\"")
            .run { context ->
                assertThat(context).hasNotFailed()
                val jdbc = context.getBean<JdbcTemplate>()
                jdbc.execute(
                    "CREATE TABLE \"custom marker\" (idempotency_hash CHAR(64) PRIMARY KEY, " +
                        "scope VARCHAR(200), created_at TIMESTAMP(6), expires_at TIMESTAMP(6))",
                )
                val once = context.getBean<IdempotencyOperations>()
                val request = IdempotencyRequest.of("custom-table", "key")
                once.executeWithoutResult(request) { }
                assertThat(once.executeWithoutResult(request) { error("duplicate must skip") }.wasExecuted()).isFalse()
                assertThat(jdbc.queryForObject<Int>("SELECT COUNT(*) FROM \"custom marker\"")).isEqualTo(1)
                assertThat(jdbc.queryForObject<Int>("SELECT COUNT(*) FROM once_marker")).isZero()
            }
    }

    @Test
    fun `a custom repository bean replaces default JDBC persistence`() {
        val dataSource = createTestMarkerDataSource("custom_once")
        val manager = JdbcTransactionManager(dataSource)
        val repository = createTestRepository(dataSource, manager, "custom_once")
        runner
            .withBean(DataSource::class.java, { dataSource })
            .withBean(JdbcTransactionManager::class.java, { manager })
            .withBean(IdempotencyMarkerRepository::class.java, { repository })
            .run { context ->
                assertThat(context).hasNotFailed()
                assertThat(context.getBeansOfType<IdempotencyMarkerRepository>()).hasSize(1)
                val once = context.getBean<DefaultIdempotencyOperations>()
                assertThat(context.getBean<IdempotencyMarkerRepository>()).isSameAs(repository)
                once.executeWithoutResult(IdempotencyRequest.of("custom-repository", "key")) { }
                assertThat(countMarkerRows(dataSource, "custom_once")).isEqualTo(1)
            }
    }

    private fun countTables(dataSource: DataSource): Int =
        checkNotNull(
            JdbcTemplate(
                dataSource,
            ).queryForObject<Int>("SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_NAME = 'ONCE_MARKER'"),
        )
}
