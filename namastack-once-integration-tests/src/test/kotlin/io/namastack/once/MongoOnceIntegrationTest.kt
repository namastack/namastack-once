package io.namastack.once

import com.mongodb.MongoException
import com.mongodb.client.MongoClient
import com.mongodb.client.MongoClients
import io.namastack.once.IdempotencyResult.Duplicate
import io.namastack.once.config.JdbcOnceAutoConfiguration
import io.namastack.once.config.MongoOnceAutoConfiguration
import io.namastack.once.config.OnceCoreInfrastructureAutoConfiguration
import io.namastack.once.config.OnceCoreSchedulingAutoConfiguration
import io.namastack.once.config.OnceCoreThreadingAutoConfiguration
import io.namastack.once.instrumentation.OnceInstrumentation
import io.namastack.once.retention.DurationIdempotencyRetentionPolicy
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.bson.Document
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.autoconfigure.task.TaskSchedulingAutoConfiguration
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.dao.DataAccessException
import org.springframework.data.domain.Sort
import org.springframework.data.mongodb.MongoDatabaseFactory
import org.springframework.data.mongodb.MongoTransactionManager
import org.springframework.data.mongodb.core.MongoTemplate
import org.springframework.data.mongodb.core.SimpleMongoClientDatabaseFactory
import org.springframework.data.mongodb.core.index.Index
import org.springframework.data.mongodb.core.query.Query
import org.springframework.jdbc.support.JdbcTransactionManager
import org.springframework.transaction.TransactionSystemException
import org.springframework.transaction.UnexpectedRollbackException
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionTemplate
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.mongodb.MongoDBContainer
import java.io.IOException
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit.MILLISECONDS
import java.util.concurrent.TimeUnit.SECONDS
import java.util.concurrent.TimeoutException
import java.util.function.Supplier
import javax.sql.DataSource

@Tag("database")
@Testcontainers
class MongoOnceIntegrationTest {
    private lateinit var client: MongoClient
    private lateinit var factory: MongoDatabaseFactory
    private lateinit var mongo: MongoTemplate
    private lateinit var manager: MongoTransactionManager
    private lateinit var repository: MongoIdempotencyMarkerRepository
    private lateinit var once: DefaultIdempotencyOperations
    private lateinit var clock: MutableOnceClock

    @BeforeEach
    fun prepareDatabase() {
        client = MongoClients.create(container.replicaSetUrl)
        factory = SimpleMongoClientDatabaseFactory(client, "once_${UUID.randomUUID().toString().replace("-", "")}")
        mongo = MongoTemplate(factory)
        mongo.createCollection("once_marker")
        mongo.createCollection("business_effect")
        mongo
            .indexOps(
                "once_marker",
            ).createIndex(Index().on("expiresAtMicros", Sort.Direction.ASC).on("_id", Sort.Direction.ASC))
        manager = MongoTransactionManager(factory)
        repository = MongoIdempotencyMarkerRepository(mongo, manager, "once_marker")
        clock = MutableOnceClock()
        once = operations(repository, manager)
    }

    @AfterEach
    fun closeClient() {
        if (::client.isInitialized) client.close()
    }

    @Test
    fun `auto configuration instruments MongoDB execution and committed cleanup batches`() {
        var executions = 0
        var cleanupBatches = 0
        val instrumentation =
            object : OnceInstrumentation {
                override fun <T> execute(
                    scope: String,
                    action: () -> IdempotencyResult<T>,
                ): IdempotencyResult<T> {
                    executions++
                    return action()
                }

                override fun cleanup(
                    backend: String,
                    cutoff: Instant,
                    batchSize: Int,
                    action: () -> Int,
                ): Int {
                    assertThat(backend).isEqualTo("mongodb")
                    cleanupBatches++
                    return action()
                }
            }
        contextRunner().withBean(OnceInstrumentation::class.java, Supplier { instrumentation }).run { context ->
            assertThat(context).hasNotFailed()
            val configured = context.getBean(IdempotencyOperations::class.java)
            configured.execute(expiring("observed")) { IdempotencyResult.executed() }
            assertThat(configured.execute<Void?>(expiring("observed")) { error("must skip") }.wasExecuted()).isFalse()
            clock.advance(Duration.ofSeconds(1))
            assertThat(
                context.getBean(IdempotencyMarkerRepository::class.java).cleanup(clock.instant(), 2),
            ).isEqualTo(1)
            assertThat(executions).isEqualTo(2)
            assertThat(cleanupBatches).isEqualTo(1)
        }
    }

    @Test
    fun `execution commits the marker and business document then skips a duplicate`() {
        val identity = request("one")
        val first =
            once.execute(identity) {
                effect("one")
                IdempotencyResult.executed("fresh")
            }
        val duplicate = once.execute<String>(identity) { error("duplicate must skip") }
        assertThat(first.fold({ it }, { "duplicate" })).isEqualTo("fresh")
        assertThat(duplicate.wasExecuted()).isFalse()
        assertThat(markers()).isEqualTo(1)
        assertThat(effects()).isEqualTo(1)
        assertThat(
            mongo.findOne(Query(), Document::class.java, "once_marker")!!.getString("_id"),
        ).matches("[0-9a-f]{64}")
    }

    @Test
    fun `action failure rolls back all writes and permits a later execution`() {
        assertThatThrownBy {
            once.execute<Void?>(request("retry")) {
                effect("retry")
                error("business failure")
            }
        }.isInstanceOf(IllegalStateException::class.java).hasMessage("business failure")
        assertThat(markers()).isZero()
        assertThat(effects()).isZero()
        assertThat(
            once
                .execute(request("retry")) {
                    effect("retry")
                    IdempotencyResult.executed()
                }.wasExecuted(),
        ).isTrue()
        assertThat(markers()).isEqualTo(1)
        assertThat(effects()).isEqualTo(1)
    }

    @Test
    fun `checked failures retain their identity after MongoDB rollback`() {
        val failure = IOException("application failure")
        assertThatThrownBy {
            once.execute<Void?>(request("checked")) {
                effect("checked")
                throw failure
            }
        }.isSameAs(failure)
        assertThat(markers()).isZero()
        assertThat(effects()).isZero()
    }

    @Test
    fun `invalid result wrappers roll back while null business values remain valid`() {
        @Suppress("UNCHECKED_CAST")
        val nullAction =
            Supplier<IdempotencyResult<String>?> {
                effect("null")
                null
            } as Supplier<IdempotencyResult<String>>
        assertThatThrownBy { once.execute(request("null"), nullAction) }.isInstanceOf(IllegalStateException::class.java)
        assertThatThrownBy {
            once.execute<String>(request("fake")) {
                effect("fake")
                Duplicate(IdempotencyDuplicateContext("mongo.operation", "fake", "hash"))
            }
        }.isInstanceOf(IllegalStateException::class.java)
        assertThat(markers()).isZero()
        assertThat(effects()).isZero()
        assertThat(
            once.execute(request("null-value")) { IdempotencyResult.executed<String?>(null) }.wasExecuted(),
        ).isTrue()
    }

    @Test
    fun `ambient transactions and nested Once calls are rejected before protected work`() {
        TransactionTemplate(manager).executeWithoutResult {
            assertThatThrownBy { once.execute<Void?>(request("ambient")) { error("must not run") } }
                .isInstanceOf(IllegalStateException::class.java)
            effect("outer")
        }
        assertThatThrownBy {
            once.execute<Void?>(request("nested")) {
                effect("nested")
                once.execute(request("inner")) { IdempotencyResult.executed() }
            }
        }.isInstanceOf(IllegalStateException::class.java)
        assertThat(markers()).isZero()
        assertThat(effects()).isEqualTo(1)
    }

    @Test
    fun `business services join Once and rollback-only never returns executed`() {
        assertThatThrownBy {
            once.execute(request("rollback-only")) {
                TransactionTemplate(manager).executeWithoutResult { status ->
                    effect("rollback-only")
                    status.setRollbackOnly()
                }
                IdempotencyResult.executed()
            }
        }.isInstanceOf(UnexpectedRollbackException::class.java)
        assertThat(markers()).isZero()
        assertThat(effects()).isZero()
    }

    @Test
    fun `retention permits cleanup and never replaces a marker that still exists`() {
        val identity = expiring("retained")
        once.execute(identity) {
            effect("retained")
            IdempotencyResult.executed()
        }
        clock.advance(Duration.ofSeconds(2))
        assertThat(once.execute<Void?>(identity) { error("retained marker must skip") }.wasExecuted()).isFalse()
        assertThat(repository.cleanup(clock.instant(), 100)).isEqualTo(1)
        assertThat(once.execute(identity) { IdempotencyResult.executed() }.wasExecuted()).isTrue()
        assertThat(effects()).isEqualTo(1)
    }

    @Test
    fun `BSON integer timestamps retain microseconds without millisecond rounding`() {
        clock = MutableOnceClock(Instant.parse("1970-01-01T00:00:00.123456789Z"))
        once = operations(repository, manager)
        val identity = request("micros").withRetentionPolicy(DurationIdempotencyRetentionPolicy(Duration.ofNanos(1000)))
        once.execute(identity) { IdempotencyResult.executed() }
        val marker = mongo.findOne(Query(), Document::class.java, "once_marker")!!
        assertThat(marker.getLong("createdAtMicros")).isEqualTo(123456L)
        assertThat(marker.getLong("expiresAtMicros")).isEqualTo(123457L)
        assertThat(marker).doesNotContainKey("_class")
        assertThat(repository.cleanup(Instant.parse("1970-01-01T00:00:00.123456999Z"), 1)).isZero()
        assertThat(repository.cleanup(Instant.parse("1970-01-01T00:00:00.123457Z"), 1)).isEqualTo(1)
    }

    @Test
    fun `invalid finite retention is rejected before application work`() {
        val invalid = request("invalid").withRetentionPolicy(DurationIdempotencyRetentionPolicy(Duration.ofNanos(1)))
        assertThatThrownBy { once.execute<Void?>(invalid) { error("must not run") } }
            .isInstanceOf(IllegalArgumentException::class.java)
        assertThat(markers()).isZero()
    }

    @Test
    fun `cleanup respects the batch size and preserves unlimited retention`() {
        repeat(5) { index -> once.execute(expiring("expired-$index")) { IdempotencyResult.executed() } }
        once.execute(request("forever")) { IdempotencyResult.executed() }
        clock.advance(Duration.ofSeconds(1))
        assertThat(repository.cleanup(clock.instant(), 2)).isEqualTo(2)
        assertThat(repository.cleanup(clock.instant(), 2)).isEqualTo(2)
        assertThat(repository.cleanup(clock.instant(), 2)).isEqualTo(1)
        assertThat(repository.cleanup(clock.instant(), 2)).isZero()
        assertThat(markers()).isEqualTo(1)
        assertThat(
            once.execute<Void?>(request("forever")) { error("forever marker must skip") }.wasExecuted(),
        ).isFalse()
    }

    @Test
    fun `cleanup commits independently of a surrounding MongoDB rollback`() {
        once.execute(expiring("cleanup")) { IdempotencyResult.executed() }
        clock.advance(Duration.ofSeconds(1))
        TransactionTemplate(manager).executeWithoutResult { outer ->
            effect("outer")
            assertThat(repository.cleanup(clock.instant(), 1)).isEqualTo(1)
            outer.setRollbackOnly()
        }
        assertThat(markers()).isZero()
        assertThat(effects()).isZero()
    }

    @Test
    fun `cleanup commit failure rolls back the complete selected batch`() {
        repeat(2) { once.execute(expiring("cleanup-$it")) { IdempotencyResult.executed() } }
        clock.advance(Duration.ofSeconds(1))
        val failing = FaultInjectingManager(factory, commitBeforeFailure = false)
        val cleanup = MongoIdempotencyMarkerRepository(mongo, failing, "once_marker")
        assertThatThrownBy { cleanup.cleanup(clock.instant(), 2) }.isInstanceOf(TransactionSystemException::class.java)
        assertThat(markers()).isEqualTo(2)
    }

    @Test
    fun `lost commit acknowledgement never removes a durable marker`() {
        val uncertain = operations(repository, FaultInjectingManager(factory, commitBeforeFailure = true))
        assertThatThrownBy {
            uncertain.execute(request("uncertain")) {
                effect("uncertain")
                IdempotencyResult.executed()
            }
        }.isInstanceOf(TransactionSystemException::class.java)
        assertThat(markers()).isEqualTo(1)
        assertThat(effects()).isEqualTo(1)
        assertThat(once.execute<Void?>(request("uncertain")) { error("must not repeat") }.wasExecuted()).isFalse()
    }

    @Test
    fun `another database factory cannot reserve or clean up this collection`() {
        once.execute(expiring("protected")) { IdempotencyResult.executed() }
        clock.advance(Duration.ofSeconds(1))
        val otherFactory =
            SimpleMongoClientDatabaseFactory(client, "other_${UUID.randomUUID().toString().replace("-", "")}")
        val wrongManager = MongoTransactionManager(otherFactory)
        assertThatThrownBy {
            operations(repository, wrongManager).execute<Void?>(request("wrong")) { error("must not run") }
        }.isInstanceOf(IllegalStateException::class.java)
        val wrongCleanup = MongoIdempotencyMarkerRepository(mongo, wrongManager, "once_marker")
        assertThatThrownBy { wrongCleanup.cleanup(clock.instant(), 10) }.isInstanceOf(IllegalStateException::class.java)
        TransactionTemplate(manager).executeWithoutResult { outer ->
            assertThatThrownBy { wrongCleanup.cleanup(clock.instant(), 10) }
                .isInstanceOf(IllegalStateException::class.java)
            assertThat(markers()).isEqualTo(1)
            outer.setRollbackOnly()
        }
        assertThat(markers()).isEqualTo(1)
    }

    @Test
    fun `unrelated business uniqueness conflicts propagate and roll back the marker`() {
        effect("existing")
        assertThatThrownBy {
            once.execute(request("constraint")) {
                effect("existing")
                IdempotencyResult.executed()
            }
        }.isInstanceOf(DataAccessException::class.java)
        assertThat(markers()).isZero()
        assertThat(effects()).isEqualTo(1)
    }

    @Test
    fun `reservations outside a transaction and invalid cleanup limits are rejected`() {
        val marker = IdempotencyMarker("hash", "scope", clock.instant(), null)
        assertThatThrownBy { repository.reserve(marker) }.isInstanceOf(IllegalStateException::class.java)
        assertThatThrownBy { repository.cleanup(clock.instant(), 0) }.isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `MongoDB auto configuration works when JDBC classes are present without a datasource`() {
        contextRunner().run { context ->
            assertThat(context).hasNotFailed()
            assertThat(context).hasSingleBean(MongoTransactionManager::class.java)
            assertThat(context).hasSingleBean(MongoIdempotencyMarkerRepository::class.java)
            context.getBean(IdempotencyOperations::class.java).execute(request("configured")) {
                effect("configured")
                IdempotencyResult.executed()
            }
            assertThat(markers()).isEqualTo(1)
            assertThat(effects()).isEqualTo(1)
        }
    }

    @Test
    fun `auto configuration uses the application collection name`() {
        mongo.createCollection("application_markers")
        contextRunner()
            .withPropertyValues("namastack.once.mongodb.collection-name=application_markers")
            .run { context ->
                assertThat(context).hasNotFailed()
                val configured = context.getBean(IdempotencyOperations::class.java)
                configured.execute(request("custom-collection")) { IdempotencyResult.executed() }
                assertThat(
                    configured.execute<Void?>(request("custom-collection")) { error("must skip") }.wasExecuted(),
                ).isFalse()
                assertThat(mongo.count(Query(), "application_markers")).isEqualTo(1)
                assertThat(markers()).isZero()
            }
    }

    @Test
    fun `JDBC remains the default when both persistence backends are configured`() {
        val dataSource = createTestMarkerDataSource("once_marker")
        contextRunner()
            .withBean(DataSource::class.java, Supplier { dataSource })
            .withBean(JdbcTransactionManager::class.java, Supplier { JdbcTransactionManager(dataSource) })
            .run { context ->
                assertThat(context).hasNotFailed()
                assertThat(context).hasSingleBean(JdbcIdempotencyMarkerRepository::class.java)
                assertThat(context).doesNotHaveBean(MongoTransactionManager::class.java)
                context.getBean(IdempotencyOperations::class.java).execute(request("sql-default")) {
                    IdempotencyResult.executed()
                }
                assertThat(countMarkerRows(dataSource, "once_marker")).isEqualTo(1)
                assertThat(markers()).isZero()
            }
    }

    @Test
    fun `custom MongoDB repositories replace the default persistence bean`() {
        mongo.createCollection("custom_markers")
        val custom = MongoIdempotencyMarkerRepository(mongo, manager, "custom_markers")
        contextRunner().withBean(IdempotencyMarkerRepository::class.java, Supplier { custom }).run { context ->
            assertThat(context).hasNotFailed()
            assertThat(context.getBean(IdempotencyMarkerRepository::class.java)).isSameAs(custom)
            context
                .getBean(
                    IdempotencyOperations::class.java,
                ).execute(request("custom")) { IdempotencyResult.executed() }
            assertThat(mongo.count(Query(), "custom_markers")).isEqualTo(1)
            assertThat(markers()).isZero()
        }
    }

    @Test
    fun `the default native manager supports transactional business services`() {
        contextRunner()
            .withBean(TransactionalMongoService::class.java, Supplier { TransactionalMongoService(mongo) })
            .run { context ->
                assertThat(context).hasNotFailed()
                assertThatThrownBy { context.getBean(TransactionalMongoService::class.java).writeThenFail() }
                    .isInstanceOf(IllegalStateException::class.java)
                    .hasMessage("service failure")
                assertThat(effects()).isZero()
                assertThat(markers()).isZero()
            }
    }

    @Test
    fun `the shared cleanup scheduler drains MongoDB batches and preserves retained markers`() {
        repeat(5) { once.execute(expiring("scheduled-$it")) { IdempotencyResult.executed() } }
        once.execute(request("retained")) { IdempotencyResult.executed() }
        clock.advance(Duration.ofSeconds(1))
        contextRunner()
            .withConfiguration(
                AutoConfigurations.of(
                    TaskSchedulingAutoConfiguration::class.java,
                    OnceCoreThreadingAutoConfiguration::class.java,
                    OnceCoreSchedulingAutoConfiguration::class.java,
                ),
            ).withPropertyValues("namastack.once.cleanup.batch-size=2")
            .run { context ->
                assertThat(context).hasNotFailed()
                val scheduler = context.getBean(OnceCleanupScheduler::class.java)
                assertThat(scheduler.isRunning).isTrue()
                scheduler.cleanup()
                assertThat(markers()).isEqualTo(1)
                assertThat(
                    once.execute<Void?>(request("retained")) { error("must remain protected") }.wasExecuted(),
                ).isFalse()
            }
    }

    @Test
    fun `concurrent reservations never execute two business actions after a commit`() = concurrentReservations(false)

    @Test
    fun `a competing reservation can be retried after the winner rolls back`() = concurrentReservations(true)

    @Test
    fun `cleanup cannot delete a replacement marker selected in an older transaction snapshot`() {
        val identity = expiring("replacement")
        once.execute(identity) { IdempotencyResult.executed() }
        clock.advance(Duration.ofSeconds(1))
        val selected = CountDownLatch(1)
        val release = CountDownLatch(1)
        val selectingTemplate =
            SelectingMongoTemplate(factory) {
                selected.countDown()
                check(release.await(10, SECONDS))
            }
        val staleCleanup = MongoIdempotencyMarkerRepository(selectingTemplate, manager, "once_marker")
        val executor = Executors.newSingleThreadExecutor()
        try {
            val pending = executor.submit<Int> { staleCleanup.cleanup(clock.instant(), 1) }
            check(selected.await(10, SECONDS))
            assertThat(repository.cleanup(clock.instant(), 1)).isEqualTo(1)
            once.execute(request("replacement")) { IdempotencyResult.executed() }
            release.countDown()
            assertThatThrownBy { pending.get(10, SECONDS) }
                .isInstanceOf(ExecutionException::class.java)
                .satisfies({ failure -> assertTransientConflict((failure as ExecutionException).cause!!) })
            assertThat(markers()).isEqualTo(1)
            assertThat(
                once.execute<Void?>(identity) { error("replacement must remain protected") }.wasExecuted(),
            ).isFalse()
        } finally {
            release.countDown()
            executor.shutdownNow()
        }
    }

    private fun concurrentReservations(rollbackWinner: Boolean) {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)
        try {
            val winner =
                executor.submit<IdempotencyResult<Void?>> {
                    once.execute(request("concurrent")) {
                        effect("concurrent")
                        entered.countDown()
                        check(release.await(10, SECONDS))
                        if (rollbackWinner) error("winner rollback")
                        IdempotencyResult.executed()
                    }
                }
            check(entered.await(10, SECONDS))
            val contender =
                executor.submit<IdempotencyResult<Void?>> {
                    once.execute(request("concurrent")) {
                        effect("concurrent")
                        IdempotencyResult.executed()
                    }
                }
            var contentionFailure: ExecutionException? = null
            try {
                contender.get(200, MILLISECONDS)
                error("An uncommitted reservation must not be reported as a duplicate")
            } catch (failure: ExecutionException) {
                contentionFailure = failure
                assertTransientConflict(failure.cause!!)
            } catch (_: TimeoutException) {
                // Some MongoDB versions wait for the competing transaction to complete.
            }
            release.countDown()
            if (rollbackWinner) {
                assertThatThrownBy { winner.get(10, SECONDS) }.hasCauseInstanceOf(IllegalStateException::class.java)
            } else {
                assertThat(winner.get(10, SECONDS).wasExecuted()).isTrue()
            }
            if (contentionFailure == null) {
                try {
                    assertThat(contender.get(10, SECONDS).wasExecuted()).isEqualTo(rollbackWinner)
                } catch (failure: ExecutionException) {
                    assertTransientConflict(failure.cause!!)
                    contentionFailure = failure
                }
            }
            if (rollbackWinner && contentionFailure != null) {
                once.execute(request("concurrent")) {
                    effect("concurrent")
                    IdempotencyResult.executed()
                }
            }
            assertThat(markers()).isEqualTo(1)
            assertThat(effects()).isEqualTo(1)
            assertThat(
                once.execute<Void?>(request("concurrent")) { error("must skip committed marker") }.wasExecuted(),
            ).isFalse()
        } finally {
            release.countDown()
            executor.shutdownNow()
        }
    }

    private fun assertTransientConflict(failure: Throwable) {
        val mongoFailure = generateSequence(failure) { it.cause }.filterIsInstance<MongoException>().firstOrNull()
        assertThat(mongoFailure).isNotNull()
        assertThat(mongoFailure!!.hasErrorLabel(MongoException.TRANSIENT_TRANSACTION_ERROR_LABEL)).isTrue()
    }

    private fun operations(
        store: IdempotencyMarkerRepository,
        txManager: MongoTransactionManager,
    ) = DefaultIdempotencyOperations(txManager, store, IdempotencyKeyNormalizer(), clock)

    private fun contextRunner(): ApplicationContextRunner =
        ApplicationContextRunner()
            .withConfiguration(
                AutoConfigurations.of(
                    OnceCoreInfrastructureAutoConfiguration::class.java,
                    JdbcOnceAutoConfiguration::class.java,
                    MongoOnceAutoConfiguration::class.java,
                ),
            ).withBean(MongoDatabaseFactory::class.java, Supplier { factory })
            .withBean(MongoTemplate::class.java, Supplier { mongo })
            .withBean(java.time.Clock::class.java, Supplier { clock })

    private fun request(key: String) = IdempotencyRequest.of("mongo.operation", key)

    private fun expiring(key: String) =
        request(key).withRetentionPolicy(DurationIdempotencyRetentionPolicy(Duration.ofSeconds(1)))

    private fun effect(key: String) {
        mongo.insert(Document("_id", key), "business_effect")
    }

    private fun markers(): Long = mongo.count(Query(), "once_marker")

    private fun effects(): Long = mongo.count(Query(), "business_effect")

    private class FaultInjectingManager(
        factory: MongoDatabaseFactory,
        private val commitBeforeFailure: Boolean,
    ) : MongoTransactionManager(factory) {
        override fun doCommit(transactionObject: MongoTransactionObject) {
            if (commitBeforeFailure) super.doCommit(transactionObject) else transactionObject.abortTransaction()
            throw TransactionSystemException("Simulated MongoDB commit failure")
        }
    }

    open class TransactionalMongoService(
        private val mongo: MongoTemplate,
    ) {
        @Transactional
        open fun writeThenFail() {
            mongo.insert(Document("_id", "service"), "business_effect")
            error("service failure")
        }
    }

    private class SelectingMongoTemplate(
        factory: MongoDatabaseFactory,
        private val selected: () -> Unit,
    ) : MongoTemplate(factory) {
        override fun <T : Any> find(
            query: Query,
            entityClass: Class<T>,
            collectionName: String,
        ): List<T> {
            val candidates = super.find(query, entityClass, collectionName)
            selected()
            return candidates
        }
    }

    companion object {
        @Container
        @JvmStatic
        val container = MongoDBContainer("mongo:9.0.4").withReplicaSet()
    }
}
