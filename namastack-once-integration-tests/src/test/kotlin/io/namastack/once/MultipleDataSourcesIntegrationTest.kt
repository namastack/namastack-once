package io.namastack.once

import io.namastack.once.retention.DurationIdempotencyRetentionPolicy
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.jdbc.support.JdbcTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset

class MultipleDataSourcesIntegrationTest {
    private val runner = multipleOnceTestApplicationContext()

    @Test
    fun `fixed instances isolate databases and reject participation in another datasource transaction`() {
        val first = createTestMarkerDataSource("orders_once")
        val second = createTestMarkerDataSource("billing_once")
        val firstManager = JdbcTransactionManager(first)
        val ordersOnce = createTestOnce(first, firstManager, "orders_once")
        val billingOnce = createTestOnce(second, JdbcTransactionManager(second), "billing_once")
        val request = IdempotencyRequest.of("process", "same-key")

        assertThat(ordersOnce.execute(request) { IdempotencyResult.executed() }.wasExecuted()).isTrue()
        assertThat(billingOnce.execute(request) { IdempotencyResult.executed() }.wasExecuted()).isTrue()
        assertThat(ordersOnce.execute<Void?>(request) { error("duplicate") }.wasExecuted()).isFalse()
        assertThat(billingOnce.execute<Void?>(request) { error("duplicate") }.wasExecuted()).isFalse()
        assertThat(countMarkerRows(first, "orders_once")).isEqualTo(1)
        assertThat(countMarkerRows(second, "billing_once")).isEqualTo(1)
        assertThatThrownBy {
            TransactionTemplate(firstManager).executeWithoutResult {
                billingOnce.execute<Void?>(request) { error("wrong datasource") }
            }
        }.isInstanceOf(IllegalStateException::class.java)
    }

    @Test
    fun `a repository wired to another datasource transaction fails before business work`() {
        val markerDataSource = createTestMarkerDataSource("orders_once")
        val businessDataSource = createTestMarkerDataSource("billing_once")
        val repository =
            createTestRepository(
                markerDataSource,
                JdbcTransactionManager(markerDataSource),
                "orders_once",
            )
        val once =
            DefaultIdempotencyOperations(
                JdbcTransactionManager(businessDataSource),
                repository,
                IdempotencyKeyNormalizer(),
                Clock.systemUTC(),
            )
        var actionExecuted = false

        assertThatThrownBy {
            once.execute(IdempotencyRequest.of("process", "misconfigured")) {
                actionExecuted = true
                IdempotencyResult.executed()
            }
        }.isInstanceOf(IllegalStateException::class.java)
        assertThat(actionExecuted).isFalse()
        assertThat(countMarkerRows(markerDataSource, "orders_once")).isZero()
        assertThat(countMarkerRows(businessDataSource, "billing_once")).isZero()
    }

    @Test
    fun `cleanup rejects a mismatched datasource and manager without deleting eligible markers`() {
        val markerDataSource = createTestMarkerDataSource("orders_once")
        val businessDataSource = createTestMarkerDataSource("billing_once")
        val reservationTime = Instant.parse("2026-11-12T10:00:00Z")
        val once =
            createTestOnce(
                markerDataSource,
                JdbcTransactionManager(markerDataSource),
                "orders_once",
                Clock.fixed(reservationTime, ZoneOffset.UTC),
            )
        val request =
            IdempotencyRequest
                .of("process", "expire")
                .withRetentionPolicy(DurationIdempotencyRetentionPolicy(Duration.ofSeconds(1)))
        once.execute(request) { IdempotencyResult.executed() }
        val repository =
            createTestRepository(
                markerDataSource,
                JdbcTransactionManager(businessDataSource),
                "orders_once",
            )

        assertThatThrownBy { repository.cleanup(reservationTime.plusSeconds(2), 1) }
            .isInstanceOf(IllegalStateException::class.java)
        assertThat(countMarkerRows(markerDataSource, "orders_once")).isEqualTo(1)
    }

    @Test
    fun `instances can use different tables in the same datasource with independent transactions`() {
        val dataSource = createTestMarkerDataSource("orders_once")
        createTestMarkerTable(dataSource, "billing_once")
        val manager = JdbcTransactionManager(dataSource)
        val ordersOnce = createTestOnce(dataSource, manager, "orders_once")
        val billingOnce = createTestOnce(dataSource, manager, "billing_once")
        val request = IdempotencyRequest.of("process", "same-key")

        assertThat(ordersOnce.execute(request) { IdempotencyResult.executed() }.wasExecuted()).isTrue()
        assertThat(billingOnce.execute(request) { IdempotencyResult.executed() }.wasExecuted()).isTrue()

        assertThat(countMarkerRows(dataSource, "orders_once")).isEqualTo(1)
        assertThat(countMarkerRows(dataSource, "billing_once")).isEqualTo(1)
    }
}
