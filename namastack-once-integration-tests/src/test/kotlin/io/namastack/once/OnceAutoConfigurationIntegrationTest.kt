package io.namastack.once

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.beans.factory.getBean
import org.springframework.jdbc.core.JdbcTemplate
import java.io.IOException
import java.time.Duration

class OnceAutoConfigurationIntegrationTest {
    private val runner = onceTestApplicationContext()

    @Test
    fun `starter infrastructure protects void and value methods through one core lifecycle`() {
        runner.withBean(AnnotatedOrderHandler::class.java).run { context ->
            assertThat(context).hasNotFailed()
            val handler = context.getBean<AnnotatedOrderHandler>()
            handler.process("void")
            handler.process("void")
            val created = handler.create("value")
            val duplicate = handler.create("value")
            assertThat(created.fold({ it }, { "duplicate" })).isEqualTo("created-value")
            assertThat(duplicate.wasExecuted()).isFalse()
            assertThat(handler.processWithResult("empty").wasExecuted()).isTrue()
            assertThat(handler.processWithResult("empty").wasExecuted()).isFalse()
            assertThat(countOnceRows(context.getBean<JdbcTemplate>(), "business_effect")).isEqualTo(3)
            assertThat(countOnceRows(context.getBean<JdbcTemplate>(), "once_marker")).isEqualTo(3)
        }
    }

    @Test
    fun `annotated null and duplicate results roll back before commit`() {
        runner.withBean(AnnotatedOrderHandler::class.java).run { context ->
            assertThat(context).hasNotFailed()
            val handler = context.getBean<AnnotatedOrderHandler>()
            assertThatThrownBy { handler.nullResult("null") }
                .isInstanceOf(IllegalStateException::class.java)
                .hasMessage("@Idempotent methods must return a non-null Executed result")
            assertThatThrownBy { handler.invalidResult("invalid") }
                .isInstanceOf(IllegalStateException::class.java)
                .hasMessage("@Idempotent methods must return a non-null Executed result")
            assertThat(countOnceRows(context.getBean<JdbcTemplate>(), "business_effect")).isZero()
            assertThat(countOnceRows(context.getBean<JdbcTemplate>(), "once_marker")).isZero()
        }
    }

    @Test
    fun `annotation preserves checked exceptions and rolls back all database changes`() {
        runner.withBean(AnnotatedOrderHandler::class.java).run { context ->
            assertThat(context).hasNotFailed()
            assertThatThrownBy { context.getBean<AnnotatedOrderHandler>().checkedFailure("checked") }
                .isInstanceOf(IOException::class.java)
            assertThat(countOnceRows(context.getBean<JdbcTemplate>(), "business_effect")).isZero()
            assertThat(countOnceRows(context.getBean<JdbcTemplate>(), "once_marker")).isZero()
        }
    }

    @Test
    fun `merged interface annotations and parameter keys are discovered`() {
        runner.withBean(InterfaceOrderHandler::class.java).run { context ->
            assertThat(context).hasNotFailed()
            val handler = context.getBean<OrderHandlerPort>()
            handler.handle("interface")
            handler.handle("interface")
            assertThat(countOnceRows(context.getBean<JdbcTemplate>(), "business_effect")).isEqualTo(1)
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
                val handler = context.getBean<AnnotatedOrderHandler>()
                handler.composed("composed")
                handler.composed("composed")
                handler.expiring("expiry")
                handler.expiring("expiry")
                context.getBean<MutableOnceClock>().advance(Duration.ofDays(2))
                handler.expiring("expiry")
                assertThat(countOnceRows(context.getBean<JdbcTemplate>(), "business_effect")).isEqualTo(2)

                context.getBean<OnceCleanupScheduler>().cleanup()
                handler.expiring("expiry")
                assertThat(countOnceRows(context.getBean<JdbcTemplate>(), "business_effect")).isEqualTo(3)
            }
    }

    @ParameterizedTest(name = "rejects {0}")
    @ValueSource(
        classes = [
            InvalidReturnHandler::class,
            InvalidKeyHandler::class,
            ReadOnlyOrderHandler::class,
            AsyncOrderHandler::class,
            FinalOrderHandler::class,
        ],
    )
    fun `invalid handler declarations fail startup`(handlerType: Class<*>) {
        runner.withBean(handlerType).run { context -> assertThat(context).hasFailed() }
    }

    @Test
    fun `disabling Once leaves annotated handlers running without duplicate protection`() {
        runner
            .withPropertyValues("namastack.once.enabled=false")
            .withBean(AnnotatedOrderHandler::class.java)
            .run { context ->
                assertThat(context).hasNotFailed()
                assertThat(context).doesNotHaveBean(IdempotencyOperations::class.java)
                val handler = context.getBean<AnnotatedOrderHandler>()
                handler.process("unprotected")
                handler.process("unprotected")
                val jdbc = context.getBean<JdbcTemplate>()
                assertThat(countOnceRows(jdbc, "business_effect")).isEqualTo(2)
            }
    }

    @Test
    fun `class retention policies use a unique bean or create an instance and reject ambiguity`() {
        val handler = runner.withBean(ClassRetentionOrderHandler::class.java)
        handler.run { context ->
            assertThat(context).hasNotFailed()
            context.getBean<ClassRetentionOrderHandler>().process("constructed")
        }
        handler.withBean("customPolicy", TestRetentionPolicy::class.java).run { context ->
            assertThat(context).hasNotFailed()
            context.getBean<ClassRetentionOrderHandler>().process("injected")
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
