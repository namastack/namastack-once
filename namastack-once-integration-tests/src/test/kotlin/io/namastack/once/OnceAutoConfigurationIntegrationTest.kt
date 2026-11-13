package io.namastack.once

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.jdbc.core.JdbcTemplate
import java.io.IOException
import java.time.Duration

class OnceAutoConfigurationIntegrationTest {
    private val runner = onceTestApplicationContext()

    @Test
    fun `starter infrastructure protects void and value methods through one core lifecycle`() {
        runner.withBean(AnnotatedOrderHandler::class.java).run { context ->
            assertThat(context).hasNotFailed()
            val handler = context.getBean(AnnotatedOrderHandler::class.java)
            handler.process("void")
            handler.process("void")
            val created = handler.create("value")
            val duplicate = handler.create("value")
            assertThat(created.fold({ it }, { "duplicate" })).isEqualTo("created-value")
            assertThat(duplicate.wasExecuted()).isFalse()
            assertThat(handler.processWithResult("empty").wasExecuted()).isTrue()
            assertThat(handler.processWithResult("empty").wasExecuted()).isFalse()
            assertThat(countOnceRows(context.getBean(JdbcTemplate::class.java), "business_effect")).isEqualTo(3)
            assertThat(countOnceRows(context.getBean(JdbcTemplate::class.java), "once_marker")).isEqualTo(3)
        }
    }

    @Test
    fun `annotated null and duplicate results roll back before commit`() {
        runner.withBean(AnnotatedOrderHandler::class.java).run { context ->
            assertThat(context).hasNotFailed()
            val handler = context.getBean(AnnotatedOrderHandler::class.java)
            assertThatThrownBy { handler.nullResult("null") }.isInstanceOf(IllegalStateException::class.java)
            assertThatThrownBy { handler.invalidResult("invalid") }.isInstanceOf(IllegalStateException::class.java)
            assertThat(countOnceRows(context.getBean(JdbcTemplate::class.java), "business_effect")).isZero()
            assertThat(countOnceRows(context.getBean(JdbcTemplate::class.java), "once_marker")).isZero()
        }
    }

    @Test
    fun `annotation preserves checked exceptions and rolls back all database changes`() {
        runner.withBean(AnnotatedOrderHandler::class.java).run { context ->
            assertThat(context).hasNotFailed()
            assertThatThrownBy { context.getBean(AnnotatedOrderHandler::class.java).checkedFailure("checked") }
                .isInstanceOf(IOException::class.java)
            assertThat(countOnceRows(context.getBean(JdbcTemplate::class.java), "business_effect")).isZero()
            assertThat(countOnceRows(context.getBean(JdbcTemplate::class.java), "once_marker")).isZero()
        }
    }

    @Test
    fun `merged interface annotations and parameter keys are discovered`() {
        runner.withBean(InterfaceOrderHandler::class.java).run { context ->
            assertThat(context).hasNotFailed()
            val handler = context.getBean(OrderHandlerPort::class.java)
            handler.handle("interface")
            handler.handle("interface")
            assertThat(countOnceRows(context.getBean(JdbcTemplate::class.java), "business_effect")).isEqualTo(1)
        }
    }

    @Test
    fun `composed annotations and named policies protect markers until cleanup`() {
        runner
            .withPropertyValues(
                "namastack.once.cleanup.enabled=true",
            ).withBean(AnnotatedOrderHandler::class.java)
            .run { context ->
                assertThat(context).hasNotFailed()
                val handler = context.getBean(AnnotatedOrderHandler::class.java)
                handler.composed("composed")
                handler.composed("composed")
                handler.expiring("expiry")
                handler.expiring("expiry")
                context.getBean(MutableOnceClock::class.java).advance(Duration.ofDays(2))
                handler.expiring("expiry")
                assertThat(countOnceRows(context.getBean(JdbcTemplate::class.java), "business_effect")).isEqualTo(2)

                context.getBean(OnceCleanupScheduler::class.java).cleanup()
                handler.expiring("expiry")
                assertThat(countOnceRows(context.getBean(JdbcTemplate::class.java), "business_effect")).isEqualTo(3)
            }
    }

    @Test
    fun `invalid signatures keys asynchronous result methods and transaction attributes fail startup`() {
        listOf(
            InvalidReturnHandler::class.java,
            InvalidKeyHandler::class.java,
            ReadOnlyOrderHandler::class.java,
            AsyncOrderHandler::class.java,
            FinalOrderHandler::class.java,
        ).forEach { handlerType ->
            runner.withBean(handlerType).run { context -> assertThat(context).hasFailed() }
        }
    }

    @Test
    fun `disabling Once leaves annotated handlers running without duplicate protection`() {
        runner.withPropertyValues("namastack.once.enabled=false").run { context ->
            assertThat(context).hasNotFailed()
            assertThat(context).doesNotHaveBean(IdempotencyOperations::class.java)
        }
        runner
            .withPropertyValues("namastack.once.enabled=false")
            .withBean(AnnotatedOrderHandler::class.java)
            .run { context ->
                assertThat(context).hasNotFailed()
                assertThat(context).doesNotHaveBean(IdempotencyOperations::class.java)
                val handler = context.getBean(AnnotatedOrderHandler::class.java)
                handler.process("unprotected")
                handler.process("unprotected")
                val jdbc = context.getBean(JdbcTemplate::class.java)
                assertThat(countOnceRows(jdbc, "business_effect")).isEqualTo(2)
                assertThat(countOnceRows(jdbc, "once_marker")).isZero()
            }
    }

    @Test
    fun `class retention policies use a unique bean or create an instance and reject ambiguity`() {
        val handler = runner.withBean(ClassRetentionOrderHandler::class.java)
        handler.run { context ->
            assertThat(context).hasNotFailed()
            context.getBean(ClassRetentionOrderHandler::class.java).process("constructed")
        }
        handler.withBean("customPolicy", TestRetentionPolicy::class.java).run { context ->
            assertThat(context).hasNotFailed()
            context.getBean(ClassRetentionOrderHandler::class.java).process("injected")
        }
        handler
            .withBean("firstPolicy", TestRetentionPolicy::class.java)
            .withBean("secondPolicy", TestRetentionPolicy::class.java)
            .run { context ->
                assertThat(context).hasFailed()
                assertThat(context.startupFailure).hasRootCauseInstanceOf(
                    org.springframework.beans.factory.NoUniqueBeanDefinitionException::class.java,
                )
            }
    }
}
