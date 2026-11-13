package io.namastack.once

import io.namastack.once.retention.DurationIdempotencyRetentionPolicy
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit.MILLISECONDS
import java.util.concurrent.TimeUnit.SECONDS
import java.util.concurrent.TimeoutException

/** Shared concurrent delivery checks, inherited by every supported database test. */
interface OnceConcurrencyContract : OnceDatabaseTestSupport {
    @Test
    fun `concurrent calls wait for commit then return duplicate`() = concurrentDelivery(rollbackFirst = false)

    @Test
    fun `concurrent waiter can execute when the first transaction rolls back`() =
        concurrentDelivery(rollbackFirst = true)

    @Test
    fun `deleted marker identities execute once under concurrent redelivery`() =
        concurrentDelivery(rollbackFirst = false, cleanupBeforeRedelivery = true)

    private fun concurrentDelivery(
        rollbackFirst: Boolean,
        cleanupBeforeRedelivery: Boolean = false,
    ) {
        if (cleanupBeforeRedelivery) {
            once.execute<Void?>(
                request("concurrent").withRetentionPolicy(DurationIdempotencyRetentionPolicy(Duration.ofSeconds(1))),
            ) { IdempotencyResult.executed() }
            clock.advance(Duration.ofSeconds(1))
            val markerStore = markerRepository
            assertThat(markerStore.cleanup(clock.instant(), 100)).isEqualTo(1)
        }
        val executor = Executors.newFixedThreadPool(2)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val secondStarted = CountDownLatch(1)
        try {
            val first =
                executor.submit<IdempotencyResult<Void?>> {
                    once.execute<Void?>(request("concurrent")) {
                        effect("concurrent")
                        entered.countDown()
                        check(release.await(10, SECONDS))
                        if (rollbackFirst) error("first rolled back")
                        IdempotencyResult.executed()
                    }
                }
            check(entered.await(10, SECONDS))
            val second =
                executor.submit<IdempotencyResult<Void?>> {
                    secondStarted.countDown()
                    once.execute<Void?>(request("concurrent")) {
                        effect("concurrent")
                        IdempotencyResult.executed()
                    }
                }
            check(secondStarted.await(10, SECONDS))
            assertThatThrownBy { second.get(150, MILLISECONDS) }.isInstanceOf(TimeoutException::class.java)
            release.countDown()
            if (rollbackFirst) {
                assertThatThrownBy { first.get(10, SECONDS) }.hasCauseInstanceOf(IllegalStateException::class.java)
            } else {
                assertThat(first.get(10, SECONDS).wasExecuted()).isTrue()
            }
            assertThat(second.get(10, SECONDS).wasExecuted()).isEqualTo(rollbackFirst)
            assertThat(markers()).isEqualTo(1)
            assertThat(effects()).isEqualTo(1)
        } finally {
            release.countDown()
            executor.shutdownNow()
        }
    }
}
