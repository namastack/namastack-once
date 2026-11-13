package io.namastack.once

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Primary
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.annotation.EnableTransactionManagement
import org.springframework.transaction.interceptor.DefaultTransactionAttribute
import org.springframework.transaction.interceptor.NameMatchTransactionAttributeSource
import org.springframework.transaction.interceptor.TransactionAttributeSource
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager
import org.springframework.transaction.support.TransactionTemplate

class OnceTransactionAdviceIntegrationTest {
    private val runner = onceTestApplicationContext()

    @Test
    fun `transaction annotations that start or join a transaction fail startup`() {
        listOf(TransactionalOrderHandler::class.java, RequiresNewOrderHandler::class.java).forEach { handler ->
            runner.withBean(handler).run { context ->
                assertThat(context).hasFailed()
                assertThat(context.startupFailure)
                    .rootCause()
                    .isInstanceOf(IllegalArgumentException::class.java)
                    .hasMessageContaining("Once owns its transaction")
            }
        }
    }

    @Test
    fun `suspension advice runs before Once regardless of transaction advisor order`() {
        listOf(EarlyTransactionConfiguration::class.java, LateTransactionConfiguration::class.java).forEach { config ->
            runner.withUserConfiguration(config).withBean(NonTransactionalOrderHandler::class.java).run { context ->
                assertThat(context).hasNotFailed()
                val handler = context.getBean(NonTransactionalOrderHandler::class.java)
                val transaction = TransactionTemplate(context.getBean(PlatformTransactionManager::class.java))
                val jdbc = context.getBean(JdbcTemplate::class.java)
                transaction.executeWithoutResult { status ->
                    jdbc.update("INSERT INTO business_effect (id, amount) VALUES ('outer', 1)")
                    handler.handle("owned")
                    status.setRollbackOnly()
                }
                handler.handle("owned")
                assertThat(countOnceRows(jdbc, "business_effect")).isEqualTo(1)
                assertThat(countOnceRows(jdbc, "once_marker")).isEqualTo(1)
                assertThatThrownBy { handler.handle("failed", fail = true) }
                    .isInstanceOf(IllegalStateException::class.java)
                assertThat(countOnceRows(jdbc, "business_effect")).isEqualTo(1)
                assertThat(countOnceRows(jdbc, "once_marker")).isEqualTo(1)
            }
        }
    }

    @Test
    fun `suspension advice isolates failing actions invoked from completion callbacks`() {
        runner
            .withUserConfiguration(LateTransactionConfiguration::class.java)
            .withBean(NonTransactionalOrderHandler::class.java)
            .run { context ->
                assertThat(context).hasNotFailed()
                val handler = context.getBean(NonTransactionalOrderHandler::class.java)
                val transaction = TransactionTemplate(context.getBean(PlatformTransactionManager::class.java))
                val jdbc = context.getBean(JdbcTemplate::class.java)
                listOf(false, true).forEach { rollback ->
                    val failures = mutableListOf<Throwable>()
                    transaction.executeWithoutResult { status ->
                        TransactionSynchronizationManager.registerSynchronization(
                            object : TransactionSynchronization {
                                private fun invokeHandler() {
                                    try {
                                        handler.handle("late", fail = true)
                                    } catch (failure: Throwable) {
                                        failures.add(failure)
                                    }
                                }

                                override fun afterCommit() = invokeHandler()

                                override fun afterCompletion(status: Int) = invokeHandler()
                            },
                        )
                        if (rollback) status.setRollbackOnly()
                    }
                    assertThat(failures).hasSize(if (rollback) 1 else 2)
                    failures.forEach { failure ->
                        assertThat(failure)
                            .isInstanceOf(IllegalStateException::class.java)
                            .hasMessage("Business action failed")
                    }
                    assertThat(countOnceRows(jdbc, "business_effect")).isZero()
                    assertThat(countOnceRows(jdbc, "once_marker")).isZero()
                }
            }
    }

    @Test
    fun `Once uses the transaction attribute source selected by Spring advice`() {
        runner
            .withUserConfiguration(CustomTransactionMetadata::class.java)
            .withBean(TransactionalOrderHandler::class.java)
            .run { context ->
                assertThat(context).hasNotFailed()
                val handler = context.getBean(TransactionalOrderHandler::class.java)
                val jdbc = context.getBean(JdbcTemplate::class.java)
                val transaction = TransactionTemplate(context.getBean(PlatformTransactionManager::class.java))
                transaction.executeWithoutResult { status ->
                    jdbc.update("INSERT INTO business_effect (id, amount) VALUES ('outer', 1)")
                    handler.process("custom-metadata")
                    status.setRollbackOnly()
                }
                handler.process("custom-metadata")
                assertThat(countOnceRows(jdbc, "business_effect")).isEqualTo(1)
                assertThat(countOnceRows(jdbc, "once_marker")).isEqualTo(1)
            }
    }

    @Configuration(proxyBeanMethods = false)
    @EnableTransactionManagement
    class CustomTransactionMetadata {
        @Bean
        @Primary
        fun customTransactionAttributeSource(): TransactionAttributeSource =
            NameMatchTransactionAttributeSource().apply {
                addTransactionalMethod(
                    "process",
                    DefaultTransactionAttribute(TransactionDefinition.PROPAGATION_NOT_SUPPORTED),
                )
            }
    }
}
