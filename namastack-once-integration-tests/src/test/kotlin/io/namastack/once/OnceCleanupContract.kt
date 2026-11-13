package io.namastack.once

import io.namastack.once.config.OnceCleanupProperties
import io.namastack.once.retention.DurationIdempotencyRetentionPolicy
import io.namastack.once.retention.ForeverIdempotencyRetentionPolicy
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatIllegalArgumentException
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.jdbc.support.JdbcTransactionManager
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler
import org.springframework.transaction.support.TransactionTemplate
import java.time.Clock
import java.time.Duration
import java.time.LocalDateTime
import java.time.ZoneOffset.UTC
import java.time.temporal.ChronoUnit.MICROS
import java.util.Optional
import java.util.TimeZone
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit.SECONDS

/** Shared retention and cleanup checks, inherited by every supported database test. */
interface OnceCleanupContract : OnceDatabaseTestSupport {
    @Test
    fun `cleanup rejects a wrong manager even when an outer transaction binds the correct datasource`() {
        once.execute(
            request("protected").withRetentionPolicy(DurationIdempotencyRetentionPolicy(Duration.ofSeconds(1))),
        ) {
            IdempotencyResult.executed()
        }
        clock.advance(Duration.ofSeconds(1))
        val otherDataSource = createTestMarkerDataSource("once_marker")
        val wrongCleanup = createTestRepository(dataSource, JdbcTransactionManager(otherDataSource))

        TransactionTemplate(manager).executeWithoutResult { outer ->
            assertThatThrownBy { wrongCleanup.cleanup(clock.instant(), 10) }
                .isInstanceOf(IllegalStateException::class.java)
            assertThat(markers()).isEqualTo(1)
            outer.setRollbackOnly()
        }
        assertThat(markers()).isEqualTo(1)
    }

    @Test
    fun `retention only permits cleanup and an existing marker always skips the action`() {
        val retainedRequest =
            request("retained")
                .withRetentionPolicy(DurationIdempotencyRetentionPolicy(Duration.ofDays(2)))
        once.execute(retainedRequest) {
            effect("before-cleanup")
            IdempotencyResult.executed()
        }

        clock.advance(Duration.ofDays(2).minusNanos(1000))
        assertThat(once.execute<Void?>(retainedRequest) { error("marker still exists") }.wasExecuted()).isFalse()
        clock.advance(Duration.ofNanos(1000))

        val unlimitedRequest = retainedRequest.withRetentionPolicy(ForeverIdempotencyRetentionPolicy)
        assertThat(
            once.execute<Void?>(unlimitedRequest) { error("eligible marker still exists") }.wasExecuted(),
        ).isFalse()
        assertThat(markers()).isEqualTo(1)
        assertThat(effects()).isEqualTo(1)

        val markerStore = markerRepository
        assertThat(markerStore.cleanup(clock.instant(), 100)).isEqualTo(1)
        assertThat(markers()).isZero()
        assertThat(
            once
                .execute(retainedRequest) {
                    effect("after-cleanup")
                    IdempotencyResult.executed()
                }.wasExecuted(),
        ).isTrue()
        assertThat(markers()).isEqualTo(1)
        assertThat(effects()).isEqualTo(2)
    }

    @Test
    fun `forever markers do not expire and invalid policies do not invoke the action`() {
        once.execute<Void?>(request("forever")) { IdempotencyResult.executed() }
        clock.advance(Duration.ofDays(3650))
        assertThat(once.execute<Void?>(request("forever")) { error("must skip") }.wasExecuted()).isFalse()
        val invalid = request("invalid").withRetentionPolicy { _, createdAt -> Optional.of(createdAt) }
        assertThatIllegalArgumentException().isThrownBy { once.execute<Void?>(invalid) { error("must not execute") } }
        assertThat(markers()).isEqualTo(1)
    }

    @Test
    fun `timestamp bindings preserve UTC microseconds and expiry outside the JVM default timezone`() {
        val original = TimeZone.getDefault()
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("Pacific/Auckland"))
            clock.advance(Duration.ofNanos(123456000))
            val createdAt = clock.instant().truncatedTo(MICROS)
            val finite = request("utc").withRetentionPolicy(DurationIdempotencyRetentionPolicy(Duration.ofSeconds(1)))
            once.execute(finite) { IdempotencyResult.executed() }
            val timestamps =
                jdbc.queryForObject("SELECT created_at, expires_at FROM once_marker") { row, _ ->
                    row.getObject("created_at", LocalDateTime::class.java) to
                        row.getObject("expires_at", LocalDateTime::class.java)
                }
            assertThat(timestamps.first).isEqualTo(LocalDateTime.ofInstant(createdAt, UTC))
            assertThat(timestamps.second).isEqualTo(LocalDateTime.ofInstant(createdAt.plusSeconds(1), UTC))
            assertThat(markerRepository.cleanup(createdAt.plusSeconds(1).minusNanos(1000), 10)).isZero()
            assertThat(markerRepository.cleanup(createdAt.plusSeconds(1), 10)).isEqualTo(1)
        } finally {
            TimeZone.setDefault(original)
        }
    }

    @Test
    fun `cleanup deletes only the configured batch and preserves forever markers`() {
        val finite = DurationIdempotencyRetentionPolicy(Duration.ofSeconds(1))
        repeat(3) { index ->
            once.execute<Void?>(request("expired-$index").withRetentionPolicy(finite)) {
                IdempotencyResult.executed()
            }
        }
        once.execute<Void?>(request("keep")) { IdempotencyResult.executed() }
        clock.advance(Duration.ofSeconds(1))
        val store = markerRepository
        val firstBatch = store.cleanup(clock.instant(), 2)
        assertThat(firstBatch).isEqualTo(2)
        assertThat(markers()).isEqualTo(2)

        val secondBatch = store.cleanup(clock.instant(), 2)
        assertThat(secondBatch).isEqualTo(1)
        assertThat(markers()).isEqualTo(1)
        assertThat(store.cleanup(clock.instant(), 2)).isZero()
    }

    @Test
    fun `repository cleanup commits independently when the enclosing business transaction rolls back`() {
        val retentionPolicy = DurationIdempotencyRetentionPolicy(Duration.ofSeconds(1))
        val cleanupRequest = request("cleanup-independent").withRetentionPolicy(retentionPolicy)
        once.execute(cleanupRequest) {
            IdempotencyResult.executed()
        }
        clock.advance(Duration.ofSeconds(1))
        val repository = markerRepository

        TransactionTemplate(manager).executeWithoutResult { status ->
            effect("before-cleanup")
            assertThat(repository.cleanup(clock.instant(), 2)).isEqualTo(1)
            effect("after-cleanup")
            status.setRollbackOnly()
        }

        assertThat(markers()).isZero()
        assertThat(effects()).isZero()
    }

    @Test
    fun `one cleanup pass drains multiple batches and preserves retained markers and business data`() {
        val shortRetention = DurationIdempotencyRetentionPolicy(Duration.ofSeconds(1))
        repeat(5) { index ->
            once.execute(request("cleanup-$index").withRetentionPolicy(shortRetention)) {
                effect("cleanup-$index")
                IdempotencyResult.executed()
            }
        }
        once.execute(request("cleanup-forever")) {
            effect("cleanup-forever")
            IdempotencyResult.executed()
        }
        val futureRequest =
            request("cleanup-future")
                .withRetentionPolicy(DurationIdempotencyRetentionPolicy(Duration.ofDays(1)))
        once.execute(futureRequest) {
            effect("cleanup-future")
            IdempotencyResult.executed()
        }
        clock.advance(Duration.ofSeconds(1))

        CleanupFixture(markerRepository, clock).use { fixture -> fixture.cleanup.cleanup() }

        assertThat(markers()).isEqualTo(2)
        assertThat(effects()).isEqualTo(7)
        assertThat(
            once.execute<Void?>(request("cleanup-forever")) { error("forever marker must remain") }.wasExecuted(),
        ).isFalse()
        assertThat(once.execute<Void?>(futureRequest) { error("future marker must remain") }.wasExecuted()).isFalse()
    }

    @Test
    fun `concurrent cleanup workers drain expired markers without deleting retained identities`() {
        val shortRetention = DurationIdempotencyRetentionPolicy(Duration.ofSeconds(1))
        repeat(9) { index ->
            once.execute(request("parallel-cleanup-$index").withRetentionPolicy(shortRetention)) {
                IdempotencyResult.executed()
            }
        }
        once.execute(request("parallel-cleanup-keep")) { IdempotencyResult.executed() }
        clock.advance(Duration.ofSeconds(1))

        val cleanupWorkers = List(2) { CleanupFixture(markerRepository, clock) }
        val executor = Executors.newFixedThreadPool(2)
        val start = CountDownLatch(1)
        try {
            val completions =
                cleanupWorkers.map { fixture ->
                    executor.submit<Unit> {
                        check(start.await(10, SECONDS))
                        fixture.cleanup.cleanup()
                    }
                }
            start.countDown()
            completions.forEach { completion -> completion.get(15, SECONDS) }
        } finally {
            start.countDown()
            cleanupWorkers.forEach(CleanupFixture::close)
            executor.shutdownNow()
        }

        assertThat(markers()).isEqualTo(1)
        assertThat(
            once
                .execute<Void?>(
                    request("parallel-cleanup-keep"),
                ) { error("retained identity must remain") }
                .wasExecuted(),
        ).isFalse()
    }
}

private class CleanupFixture(
    repository: IdempotencyMarkerRepository,
    clock: Clock,
) : AutoCloseable {
    private val scheduler = ThreadPoolTaskScheduler().apply { initialize() }
    val cleanup =
        OnceCleanupScheduler(listOf(repository), OnceCleanupProperties(batchSize = 2), clock, scheduler)
            .apply { start() }

    override fun close() {
        cleanup.stop()
        scheduler.destroy()
    }
}
