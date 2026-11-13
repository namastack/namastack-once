package io.namastack.once

import io.namastack.once.config.JdbcOnceAutoConfiguration
import io.namastack.once.config.OnceCoreAnnotationAutoConfiguration
import io.namastack.once.config.OnceCoreInfrastructureAutoConfiguration
import io.namastack.once.config.OnceCoreSchedulingAutoConfiguration
import io.namastack.once.config.OnceCoreThreadingAutoConfiguration
import io.namastack.once.retention.DurationIdempotencyRetentionPolicy
import io.namastack.once.retention.IdempotencyRetentionPolicy
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.autoconfigure.task.TaskSchedulingAutoConfiguration
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.io.ClassPathResource
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator
import org.springframework.jdbc.support.JdbcTransactionManager
import java.time.Duration
import java.util.UUID
import javax.sql.DataSource

fun onceTestApplicationContext(): ApplicationContextRunner =
    ApplicationContextRunner()
        .withConfiguration(
            AutoConfigurations.of(
                TaskSchedulingAutoConfiguration::class.java,
                OnceCoreThreadingAutoConfiguration::class.java,
                OnceCoreSchedulingAutoConfiguration::class.java,
                JdbcOnceAutoConfiguration::class.java,
                OnceCoreInfrastructureAutoConfiguration::class.java,
                OnceCoreAnnotationAutoConfiguration::class.java,
            ),
        ).withUserConfiguration(OnceTestDatabaseConfiguration::class.java)
        .withPropertyValues("namastack.once.cleanup.enabled=false")

fun countOnceRows(
    jdbc: JdbcTemplate,
    table: String,
): Int = checkNotNull(jdbc.queryForObject("SELECT COUNT(*) FROM $table", Int::class.java))

/** Application-owned test schema, deliberately created outside Once auto-configuration. */
@Configuration(proxyBeanMethods = false)
class OnceTestDatabaseConfiguration {
    @Bean
    fun dataSource(): DataSource =
        DriverManagerDataSource("jdbc:h2:mem:boot_${UUID.randomUUID()};DB_CLOSE_DELAY=-1", "sa", "").also {
            ResourceDatabasePopulator(ClassPathResource("schema/h2.sql")).execute(it)
            JdbcTemplate(
                it,
            ).execute("CREATE TABLE business_effect (id VARCHAR(100) PRIMARY KEY, amount INTEGER NOT NULL)")
        }

    @Bean
    fun transactionManager(dataSource: DataSource): JdbcTransactionManager = JdbcTransactionManager(dataSource)

    @Bean
    fun jdbcTemplate(dataSource: DataSource): JdbcTemplate = JdbcTemplate(dataSource)

    @Bean
    fun clock(): MutableOnceClock = MutableOnceClock()

    @Bean
    fun twoDays(): IdempotencyRetentionPolicy = DurationIdempotencyRetentionPolicy(Duration.ofDays(2))
}
