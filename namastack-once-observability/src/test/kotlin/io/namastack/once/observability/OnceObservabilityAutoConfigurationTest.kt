package io.namastack.once.observability

import io.micrometer.observation.Observation
import io.micrometer.observation.ObservationHandler
import io.micrometer.observation.ObservationRegistry
import io.namastack.once.IdempotencyOperations
import io.namastack.once.IdempotencyRequest
import io.namastack.once.IdempotencyResult
import io.namastack.once.config.JdbcOnceAutoConfiguration
import io.namastack.once.config.OnceCoreInfrastructureAutoConfiguration
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.springframework.jdbc.support.JdbcTransactionManager
import java.util.UUID
import java.util.function.Supplier
import javax.sql.DataSource

class OnceObservabilityAutoConfigurationTest {
    private val registry = ObservationRegistry.create()
    private val runner =
        ApplicationContextRunner().withConfiguration(
            AutoConfigurations.of(
                OnceObservabilityAutoConfiguration::class.java,
                OnceCoreInfrastructureAutoConfiguration::class.java,
                JdbcOnceAutoConfiguration::class.java,
            ),
        )

    @Test
    fun `no registry means no Micrometer adapter`() {
        runner.run { assertThat(it).hasNotFailed().doesNotHaveBean(MicrometerOnceInstrumentation::class.java) }
    }

    @Test
    fun `disabled Once supplies no adapter`() {
        runner
            .withBean(ObservationRegistry::class.java, Supplier { registry })
            .withPropertyValues("namastack.once.enabled=false")
            .run { assertThat(it).hasNotFailed().doesNotHaveBean(MicrometerOnceInstrumentation::class.java) }
    }

    @Test
    fun `custom adapter replaces the default`() {
        val custom = MicrometerOnceInstrumentation({ registry })
        runner
            .withBean(ObservationRegistry::class.java, Supplier { registry })
            .withBean(MicrometerOnceInstrumentation::class.java, Supplier { custom })
            .run { assertThat(it.getBean(MicrometerOnceInstrumentation::class.java)).isSameAs(custom) }
    }

    @Test
    fun `auto configuration wires observations into the JDBC execution service and custom conventions`() {
        val contexts = mutableListOf<Observation.Context>()
        registry.observationConfig().observationHandler(
            object : ObservationHandler<Observation.Context> {
                override fun supportsContext(context: Observation.Context): Boolean = true

                override fun onStop(context: Observation.Context) {
                    contexts += context
                }
            },
        )
        val ds = DriverManagerDataSource("jdbc:h2:mem:${UUID.randomUUID()};DB_CLOSE_DELAY=-1", "sa", "")
        JdbcTemplate(
            ds,
        ).execute(
            "CREATE TABLE once_marker (idempotency_hash CHAR(64) PRIMARY KEY, scope VARCHAR(200), created_at TIMESTAMP, expires_at TIMESTAMP)",
        )
        val custom =
            object : OnceExecuteObservationConvention {
                override fun getName(): String = "custom.once"
            }
        runner
            .withBean(ObservationRegistry::class.java, Supplier { registry })
            .withBean(DataSource::class.java, Supplier { ds })
            .withBean(JdbcTransactionManager::class.java, Supplier { JdbcTransactionManager(ds) })
            .withBean(OnceExecuteObservationConvention::class.java, Supplier { custom })
            .run {
                assertThat(it).hasNotFailed()
                it.getBean(IdempotencyOperations::class.java).execute(IdempotencyRequest.of("orders", "1")) {
                    IdempotencyResult.executed()
                }
                assertThat(contexts.map { context -> context.name }).containsExactly("custom.once")
            }
    }
}
