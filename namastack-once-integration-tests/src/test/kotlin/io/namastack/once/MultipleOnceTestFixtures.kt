package io.namastack.once

import io.namastack.once.annotation.Idempotent
import io.namastack.once.config.JdbcOnceAutoConfiguration
import io.namastack.once.config.OnceCoreAnnotationAutoConfiguration
import io.namastack.once.config.OnceCoreInfrastructureAutoConfiguration
import io.namastack.once.config.OnceCoreSchedulingAutoConfiguration
import io.namastack.once.config.OnceCoreThreadingAutoConfiguration
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.autoconfigure.task.TaskSchedulingAutoConfiguration
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Primary
import org.springframework.core.io.ClassPathResource
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.springframework.jdbc.support.JdbcTransactionManager
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.util.UUID
import javax.sql.DataSource

fun multipleOnceTestApplicationContext(): ApplicationContextRunner =
    ApplicationContextRunner().withConfiguration(
        AutoConfigurations.of(
            OnceCoreInfrastructureAutoConfiguration::class.java,
            JdbcOnceAutoConfiguration::class.java,
            OnceCoreAnnotationAutoConfiguration::class.java,
            TaskSchedulingAutoConfiguration::class.java,
            OnceCoreThreadingAutoConfiguration::class.java,
            OnceCoreSchedulingAutoConfiguration::class.java,
        ),
    )

fun countMarkerRows(
    dataSource: DataSource,
    table: String,
): Int = checkNotNull(JdbcTemplate(dataSource).queryForObject("SELECT COUNT(*) FROM $table", Int::class.java))

fun createTestOnce(
    dataSource: DataSource,
    manager: PlatformTransactionManager,
    tableName: String = "once_marker",
    clock: Clock = Clock.systemUTC(),
): DefaultIdempotencyOperations {
    val repository = createTestRepository(dataSource, manager, tableName)
    return DefaultIdempotencyOperations(manager, repository, IdempotencyKeyNormalizer(), clock)
}

fun createTestMarkerDataSource(tableName: String): DataSource {
    val dataSource =
        DriverManagerDataSource("jdbc:h2:mem:multi_${UUID.randomUUID()};DB_CLOSE_DELAY=-1", "sa", "")
    createTestMarkerTable(dataSource, tableName)
    return dataSource
}

fun createTestMarkerTable(
    dataSource: DataSource,
    tableName: String,
) {
    val schema = ClassPathResource("schema/h2.sql").inputStream.bufferedReader().use { it.readText() }
    schema.replace("once_marker", tableName).split(';').filter(String::isNotBlank).forEach {
        JdbcTemplate(dataSource).execute(it)
    }
}

@Configuration(proxyBeanMethods = false)
class TwoOnceInstances {
    @Bean fun clock(): MutableOnceClock = MutableOnceClock()

    @Bean fun ordersDataSource(): DataSource = createTestMarkerDataSource("orders_once")

    @Bean fun billingDataSource(): DataSource = createTestMarkerDataSource("billing_once")

    @Bean fun ordersManager(
        @Qualifier("ordersDataSource") dataSource: DataSource,
    ) = JdbcTransactionManager(dataSource)

    @Bean fun billingManager(
        @Qualifier("billingDataSource") dataSource: DataSource,
    ) = JdbcTransactionManager(dataSource)

    @Bean
    @Primary
    fun ordersOnce(
        @Qualifier("ordersDataSource") dataSource: DataSource,
        @Qualifier("ordersManager") manager: JdbcTransactionManager,
        clock: Clock,
    ): IdempotencyOperations = createTestOnce(dataSource, manager, "orders_once", clock = clock)

    @Bean
    fun billingOnce(
        @Qualifier("billingDataSource") dataSource: DataSource,
        @Qualifier("billingManager") manager: JdbcTransactionManager,
        clock: Clock,
    ): IdempotencyOperations = createTestOnce(dataSource, manager, "billing_once", clock = clock)
}

open class NamedHandlers {
    @Idempotent(scope = "process", key = "#id", operationsBean = "ordersOnce")
    open fun order(id: String) = Unit

    @Idempotent(scope = "process", key = "#id", operationsBean = "billingOnce")
    open fun invoice(id: String) = Unit

    @Idempotent(scope = "process", key = "#id")
    open fun defaultOrder(id: String) = Unit
}

open class ExplicitInstanceHandler {
    @Idempotent(scope = "process", key = "#id", operationsBean = "ordersOnce")
    open fun process(id: String) = Unit
}

open class DefaultInstanceHandler {
    @Idempotent(scope = "process", key = "#id")
    open fun process(id: String) = Unit
}

open class UnknownInstanceHandler {
    @Idempotent(scope = "process", key = "#id", operationsBean = "unknownOnce")
    open fun process(id: String) = Unit
}

open class ConflictingTransactionHandler {
    @Idempotent(scope = "process", key = "#id", operationsBean = "ordersOnce")
    @Transactional("billingManager")
    open fun process(id: String) = Unit
}
