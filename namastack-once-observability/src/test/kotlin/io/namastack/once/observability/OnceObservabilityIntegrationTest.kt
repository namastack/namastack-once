package io.namastack.once.observability

import io.micrometer.core.instrument.observation.DefaultMeterObservationHandler
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.micrometer.observation.Observation
import io.micrometer.observation.ObservationHandler
import io.micrometer.observation.ObservationRegistry
import io.namastack.once.DefaultIdempotencyOperations
import io.namastack.once.IdempotencyKeyNormalizer
import io.namastack.once.IdempotencyRequest
import io.namastack.once.IdempotencyResult
import io.namastack.once.JdbcIdempotencyMarkerRepository
import io.namastack.once.config.JdbcDatabaseType
import io.namastack.once.query.JdbcOnceMarkerQueriesFactory
import io.namastack.once.retention.DurationIdempotencyRetentionPolicy
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.springframework.jdbc.support.JdbcTransactionManager
import org.springframework.transaction.support.DefaultTransactionStatus
import java.io.IOException
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID

class OnceObservabilityIntegrationTest {
    private val registry = ObservationRegistry.create()
    private val meters = SimpleMeterRegistry()
    private val contexts = mutableListOf<Observation.Context>()
    private val parents = mutableListOf<String?>()
    private val dataSource = DriverManagerDataSource("jdbc:h2:mem:${UUID.randomUUID()};DB_CLOSE_DELAY=-1", "sa", "")
    private val jdbc = JdbcTemplate(dataSource)
    private var failCommit = false
    private val manager =
        object : JdbcTransactionManager(dataSource) {
            override fun doCommit(status: DefaultTransactionStatus) {
                if (failCommit) throw IllegalStateException("commit failed")
                super.doCommit(status)
            }
        }.apply { isRollbackOnCommitFailure = true }
    private val instrumentation = MicrometerOnceInstrumentation({ registry })
    private val repository =
        JdbcIdempotencyMarkerRepository(dataSource, manager, instrumentation) {
            JdbcOnceMarkerQueriesFactory.create(JdbcDatabaseType.H2, "once_marker")
        }
    private val once =
        DefaultIdempotencyOperations(
            manager,
            repository,
            IdempotencyKeyNormalizer(),
            Clock.systemUTC(),
            instrumentation,
        )

    init {
        jdbc.execute(
            "CREATE TABLE once_marker (idempotency_hash CHAR(64) PRIMARY KEY, scope VARCHAR(200), created_at TIMESTAMP(6), expires_at TIMESTAMP(6))",
        )
        jdbc.execute("CREATE TABLE effects (id INT)")
        registry.observationConfig().observationHandler(DefaultMeterObservationHandler(meters))
        registry.observationConfig().observationHandler(
            object : ObservationHandler<Observation.Context> {
                override fun supportsContext(context: Observation.Context): Boolean = true

                override fun onStart(context: Observation.Context) {
                    parents += context.parentObservation?.contextView?.name
                }

                override fun onStop(context: Observation.Context) {
                    contexts += context
                }
            },
        )
    }

    @Test
    fun `execution preserves parent scope and duplicate is not an error`() {
        val request = IdempotencyRequest.of("orders.create", "secret-key")
        val expected = IdempotencyResult.executed("order")
        Observation.createNotStarted("http.request", registry).observe {
            assertThat(once.execute(request) { expected }).isSameAs(expected)
            assertThat(once.execute<String>(request) { error("must skip") }.wasExecuted()).isFalse()
        }
        val executions = contexts.filterIsInstance<OnceExecuteObservationContext>()
        assertThat(executions.map { it.outcome }).containsExactly("executed", "duplicate")
        assertThat(executions.map { it.error }).containsOnlyNulls()
        assertThat(parents).containsExactly(null, "http.request", "http.request")
        assertThat(registry.currentObservation).isNull()
        assertThat(
            meters
                .get("once.execute")
                .tag("once.outcome", "executed")
                .timer()
                .count(),
        ).isEqualTo(1)
        assertThat(
            meters
                .get("once.execute")
                .tag("once.outcome", "duplicate")
                .timer()
                .count(),
        ).isEqualTo(1)
        assertThat(
            meters.meters
                .flatMap {
                    it.id.tags
                }.map { it.key },
        ).doesNotContain("once.scope", "once.key", "once.key.hash")
    }

    @Test
    fun `checked application failures are restored and rollback before observation completes`() {
        val failure = IOException("business failure")
        assertThatThrownBy {
            once.execute<Void?>(IdempotencyRequest.of("orders", "failure")) {
                jdbc.update("INSERT INTO effects VALUES (1)")
                throw failure
            }
        }.isSameAs(failure)
        val context = contexts.single() as OnceExecuteObservationContext
        assertThat(context.outcome).isEqualTo("error")
        assertThat(context.error).isSameAs(failure)
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM effects", Int::class.java)).isZero()
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM once_marker", Int::class.java)).isZero()
        assertThat(registry.currentObservation).isNull()
    }

    @Test
    fun `commit failures never report executed or committed cleanup deletions`() {
        val request =
            IdempotencyRequest
                .of(
                    "orders",
                    "commit",
                ).withRetentionPolicy(DurationIdempotencyRetentionPolicy(Duration.ofSeconds(1)))
        once.execute(request) { IdempotencyResult.executed() }
        failCommit = true
        assertThatThrownBy { once.execute(IdempotencyRequest.of("orders", "other")) { IdempotencyResult.executed() } }
            .hasMessage("commit failed")
        assertThatThrownBy { repository.cleanup(Instant.now().plusSeconds(10), 5) }.hasMessage("commit failed")
        assertThat(
            contexts.filterIsInstance<OnceExecuteObservationContext>().map {
                it.outcome
            },
        ).containsExactly("executed", "error")
        val cleanup = contexts.filterIsInstance<OnceCleanupObservationContext>().single()
        assertThat(cleanup.outcome).isEqualTo("error")
        assertThat(cleanup.deletedCount).isZero()
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM once_marker", Int::class.java)).isEqualTo(1)
    }

    @Test
    fun `cleanup exposes committed count and preserves batch limits`() {
        repeat(3) {
            val request =
                IdempotencyRequest
                    .of(
                        "orders",
                        it,
                    ).withRetentionPolicy(DurationIdempotencyRetentionPolicy(Duration.ofSeconds(1)))
            once.execute(request) { IdempotencyResult.executed() }
        }
        val cutoff = Instant.now().plusSeconds(10)
        assertThat(repository.cleanup(cutoff, 2)).isEqualTo(2)
        assertThat(repository.cleanup(cutoff, 2)).isEqualTo(1)
        assertThat(repository.cleanup(cutoff, 2)).isZero()
        val batches = contexts.filterIsInstance<OnceCleanupObservationContext>()
        assertThat(batches.map { it.deletedCount }).containsExactly(2, 1, 0)
        assertThat(batches.map { it.outcome }).containsOnly("success")
        assertThat(batches.map { it.backend }).containsOnly("jdbc")
    }

    @Test
    fun `validation failures are observed without running application work`() {
        assertThatThrownBy { once.execute<Void?>(IdempotencyRequest.of("orders", " ")) { error("must not run") } }
            .isInstanceOf(IllegalArgumentException::class.java)
        assertThat((contexts.single() as OnceExecuteObservationContext).outcome).isEqualTo("error")
    }
}
