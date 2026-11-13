package io.namastack.once

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.namastack.once.config.MongoOnceAutoConfiguration
import io.namastack.once.config.OnceCoreInfrastructureAutoConfiguration
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.NoUniqueBeanDefinitionException
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.data.mongodb.MongoDatabaseFactory
import org.springframework.data.mongodb.MongoTransactionManager
import org.springframework.data.mongodb.core.MongoTemplate
import java.time.Clock
import java.util.function.Supplier

class MongoOnceAutoConfigurationTest {
    private val factory = mockk<MongoDatabaseFactory>()
    private val template = mockk<MongoTemplate>(relaxed = true) { every { mongoDatabaseFactory } returns factory }
    private val runner =
        ApplicationContextRunner()
            .withConfiguration(
                AutoConfigurations.of(
                    OnceCoreInfrastructureAutoConfiguration::class.java,
                    MongoOnceAutoConfiguration::class.java,
                ),
            ).withPropertyValues("namastack.once.cleanup.enabled=false")

    @Test
    fun `MongoDB infrastructure is absent without a MongoTemplate`() {
        runner.withBean(MongoDatabaseFactory::class.java, Supplier { factory }).run { context ->
            assertThat(context).hasNotFailed()
            assertThat(context).doesNotHaveBean(IdempotencyOperations::class.java)
            assertThat(context).doesNotHaveBean(MongoTransactionManager::class.java)
        }
    }

    @Test
    fun `a custom template needs no separate database factory bean`() {
        runner.withBean(MongoTemplate::class.java, Supplier { template }).run { context ->
            assertThat(context).hasNotFailed()
            assertThat(context).hasSingleBean(DefaultIdempotencyOperations::class.java)
            assertThat(context.getBean(MongoTransactionManager::class.java).databaseFactory).isSameAs(factory)
        }
    }

    @Test
    fun `the default transaction manager uses the templates factory`() {
        val unrelatedFactory = mockk<MongoDatabaseFactory>()
        runner
            .withBean(MongoDatabaseFactory::class.java, Supplier { unrelatedFactory })
            .withBean(MongoTemplate::class.java, Supplier { template })
            .run { context ->
                assertThat(context).hasNotFailed()
                assertThat(context.getBean(MongoTransactionManager::class.java).databaseFactory).isSameAs(factory)
                assertThat(context.getBean(IdempotencyMarkerRepository::class.java).transactionResourceKey)
                    .isSameAs(factory)
            }
    }

    @Test
    fun `MongoDB default wiring creates native transactions without opening a connection`() {
        configured().run { context ->
            assertThat(context).hasNotFailed()
            assertThat(context).hasSingleBean(DefaultIdempotencyOperations::class.java)
            assertThat(context).hasSingleBean(MongoIdempotencyMarkerRepository::class.java)
            assertThat(context).hasSingleBean(MongoTransactionManager::class.java)
            assertThat(
                context.getBean(IdempotencyMarkerRepository::class.java).transactionResourceKey,
            ).isSameAs(factory)
            verify(exactly = 0) { factory.getSession(any()) }
        }
    }

    @Test
    fun `disabling Once creates no MongoDB infrastructure`() {
        configured().withPropertyValues("namastack.once.enabled=false").run { context ->
            assertThat(context).hasNotFailed()
            assertThat(context).doesNotHaveBean(IdempotencyOperations::class.java)
            assertThat(context).doesNotHaveBean(MongoTransactionManager::class.java)
        }
    }

    @Test
    fun `custom Once implementations replace MongoDB auto configuration`() {
        val custom = mockk<IdempotencyOperations>()
        configured().withBean(IdempotencyOperations::class.java, Supplier { custom }).run { context ->
            assertThat(context).hasNotFailed()
            assertThat(context.getBean(IdempotencyOperations::class.java)).isSameAs(custom)
            assertThat(context).doesNotHaveBean(MongoTransactionManager::class.java)
        }
    }

    @Test
    fun `a custom repository replaces the default MongoDB repository`() {
        val custom = mockk<IdempotencyMarkerRepository>()
        configured().withBean(IdempotencyMarkerRepository::class.java, Supplier { custom }).run { context ->
            assertThat(context).hasNotFailed()
            assertThat(context.getBean(IdempotencyMarkerRepository::class.java)).isSameAs(custom)
            assertThat(context).hasSingleBean(DefaultIdempotencyOperations::class.java)
        }
    }

    @Test
    fun `ambiguous MongoDB transaction managers fail startup`() {
        configured()
            .withBean("first", MongoTransactionManager::class.java, Supplier { MongoTransactionManager(factory) })
            .withBean("second", MongoTransactionManager::class.java, Supplier { MongoTransactionManager(factory) })
            .run { context ->
                assertThat(context).hasFailed()
                assertThat(context.startupFailure).hasRootCauseInstanceOf(NoUniqueBeanDefinitionException::class.java)
            }
    }

    @Test
    fun `an explicitly selected native manager resolves ambiguity`() {
        configured()
            .withBean("first", MongoTransactionManager::class.java, Supplier { MongoTransactionManager(factory) })
            .withBean("second", MongoTransactionManager::class.java, Supplier { MongoTransactionManager(factory) })
            .withPropertyValues("namastack.once.transaction-manager=second")
            .run { context ->
                assertThat(context).hasNotFailed()
                assertThat(context).hasSingleBean(DefaultIdempotencyOperations::class.java)
            }
    }

    @Test
    fun `an application clock replaces the default clock`() {
        val clock = Clock.fixed(java.time.Instant.EPOCH, java.time.ZoneOffset.UTC)
        configured().withBean(Clock::class.java, Supplier { clock }).run { context ->
            assertThat(context).hasNotFailed()
            assertThat(context.getBean(Clock::class.java)).isSameAs(clock)
        }
    }

    private fun configured(): ApplicationContextRunner =
        runner
            .withBean(MongoDatabaseFactory::class.java, Supplier { factory })
            .withBean(MongoTemplate::class.java, Supplier { template })
}
