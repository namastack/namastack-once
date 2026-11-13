package io.namastack.once

import io.namastack.once.retention.DurationIdempotencyRetentionPolicy
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.NoUniqueBeanDefinitionException
import org.springframework.jdbc.support.JdbcTransactionManager
import java.time.Duration
import java.util.function.Supplier
import javax.sql.DataSource

class MultipleOnceBeansIntegrationTest {
    private val runner = multipleOnceTestApplicationContext()

    @Test
    fun `named annotations select fixed Spring instances and Primary supplies the default`() {
        runner.withUserConfiguration(TwoOnceInstances::class.java).withBean(NamedHandlers::class.java).run { context ->
            assertThat(context).hasNotFailed()
            assertThat(context).doesNotHaveBean("once")
            assertThat(context.getBeansOfType(IdempotencyOperations::class.java)).hasSize(2)
            val handlers = context.getBean(NamedHandlers::class.java)
            repeat(2) {
                handlers.order("same-key")
                handlers.invoice("same-key")
                handlers.defaultOrder("default")
            }
            assertThat(
                countMarkerRows(context.getBean("ordersDataSource", DataSource::class.java), "orders_once"),
            ).isEqualTo(2)
            assertThat(
                countMarkerRows(context.getBean("billingDataSource", DataSource::class.java), "billing_once"),
            ).isEqualTo(1)
        }
    }

    @Test
    fun `cleanup visits every fixed instance including beans declared through the API interface`() {
        runner
            .withUserConfiguration(TwoOnceInstances::class.java)
            .withPropertyValues("namastack.once.cleanup.batch-size=1")
            .run { context ->
                assertThat(context).hasNotFailed()
                val request =
                    IdempotencyRequest
                        .of("expire", "key")
                        .withRetentionPolicy(DurationIdempotencyRetentionPolicy(Duration.ofSeconds(1)))
                listOf("ordersOnce", "billingOnce").forEach { name ->
                    val once = context.getBean(name, IdempotencyOperations::class.java)
                    repeat(3) { index -> once.execute(request.copy(key = index)) { IdempotencyResult.executed() } }
                    once.execute(IdempotencyRequest.of("keep", "forever")) { IdempotencyResult.executed() }
                }
                context.getBean(MutableOnceClock::class.java).advance(Duration.ofSeconds(1))
                context.getBean(OnceCleanupScheduler::class.java).cleanup()
                assertThat(
                    countMarkerRows(context.getBean("ordersDataSource", DataSource::class.java), "orders_once"),
                ).isEqualTo(1)
                assertThat(
                    countMarkerRows(context.getBean("billingDataSource", DataSource::class.java), "billing_once"),
                ).isEqualTo(1)
            }
    }

    @Test
    fun `multiple instances need no Primary when annotations select their beans explicitly`() {
        val ordersDataSource = createTestMarkerDataSource("orders_once")
        val billingDataSource = createTestMarkerDataSource("billing_once")
        val ordersOnce = createTestOnce(ordersDataSource, JdbcTransactionManager(ordersDataSource), "orders_once")
        val billingOnce = createTestOnce(billingDataSource, JdbcTransactionManager(billingDataSource), "billing_once")
        val multipleInstances =
            runner
                .withBean("ordersOnce", IdempotencyOperations::class.java, Supplier { ordersOnce })
                .withBean("billingOnce", IdempotencyOperations::class.java, Supplier { billingOnce })

        multipleInstances.withBean(ExplicitInstanceHandler::class.java).run { context ->
            assertThat(context).hasNotFailed()
            repeat(2) { context.getBean(ExplicitInstanceHandler::class.java).process("explicit") }
        }
        multipleInstances.withBean(DefaultInstanceHandler::class.java).run { context ->
            assertThat(context).hasFailed()
            assertThat(context.startupFailure).hasRootCauseInstanceOf(
                org.springframework.beans.factory.NoUniqueBeanDefinitionException::class.java,
            )
        }
    }

    @Test
    fun `unknown instance names and conflicting transaction managers fail annotation validation`() {
        listOf(UnknownInstanceHandler::class.java, ConflictingTransactionHandler::class.java).forEach { handler ->
            runner.withUserConfiguration(TwoOnceInstances::class.java).withBean(handler).run { context ->
                assertThat(context).hasFailed()
            }
        }
    }
}
