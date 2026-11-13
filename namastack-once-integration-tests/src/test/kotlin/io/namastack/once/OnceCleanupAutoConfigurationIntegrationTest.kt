package io.namastack.once

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledForJreRange
import org.junit.jupiter.api.condition.JRE
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.scheduling.concurrent.SimpleAsyncTaskScheduler
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit.SECONDS
import java.util.function.Supplier

class OnceCleanupAutoConfigurationIntegrationTest {
    private val runner = onceTestApplicationContext()

    @Test
    fun `cleanup uses its own lifecycle and deletes expired markers without deleting business data`() {
        runner
            .withPropertyValues("namastack.once.cleanup.enabled=true")
            .withBean(AnnotatedOrderHandler::class.java)
            .run { context ->
                assertThat(context).hasNotFailed()
                context.getBean(AnnotatedOrderHandler::class.java).expiring("cleanup")
                context.getBean(MutableOnceClock::class.java).advance(Duration.ofDays(2))
                context.getBean(OnceCleanupScheduler::class.java).cleanup()
                val jdbc = context.getBean(JdbcTemplate::class.java)
                assertThat(countOnceRows(jdbc, "once_marker")).isZero()
                assertThat(countOnceRows(jdbc, "business_effect")).isEqualTo(1)
            }
    }

    @Test
    fun `cleanup scheduler is Spring managed and only created when cleanup is enabled`() {
        runner.run { context ->
            assertThat(context).doesNotHaveBean(OnceCleanupScheduler.SCHEDULER_NAME)
            assertThat(context).doesNotHaveBean(OnceCleanupScheduler::class.java)
        }
        runner.withPropertyValues("namastack.once.cleanup.enabled=true").run { context ->
            assertThat(context).hasNotFailed()
            assertThat(
                context.getBean(OnceCleanupScheduler.SCHEDULER_NAME),
            ).isInstanceOf(ThreadPoolTaskScheduler::class.java)
            assertThat(context.getBean(OnceCleanupScheduler::class.java).isRunning).isTrue()
            assertThat(
                context.beanFactory.getBeanDefinition(OnceCleanupScheduler.SCHEDULER_NAME).isAutowireCandidate,
            ).isFalse()
        }
    }

    @Test
    fun `a custom named scheduler drives cleanup and remains available when cleanup is stopped`() {
        val scheduler = ThreadPoolTaskScheduler()
        val scheduledPassCompleted = CountDownLatch(1)
        scheduler.setTaskDecorator { task ->
            Runnable {
                task.run()
                scheduledPassCompleted.countDown()
            }
        }
        runner
            .withPropertyValues("namastack.once.cleanup.enabled=true", "namastack.once.cleanup.interval=10ms")
            .withBean(OnceCleanupScheduler.SCHEDULER_NAME, ThreadPoolTaskScheduler::class.java, Supplier { scheduler })
            .run { context ->
                assertThat(context).hasNotFailed()
                assertThat(context.getBean(OnceCleanupScheduler.SCHEDULER_NAME)).isSameAs(scheduler)
                check(scheduledPassCompleted.await(10, SECONDS))
                context.getBean(OnceCleanupScheduler::class.java).stop()
                assertThat(scheduler.scheduledThreadPoolExecutor.isShutdown).isFalse()
            }
        assertThat(scheduler.scheduledThreadPoolExecutor.isShutdown).isTrue()
    }

    @Test
    @EnabledForJreRange(min = JRE.JAVA_21)
    fun `Boot virtual threads select a virtual cleanup scheduler`() {
        runner
            .withPropertyValues("namastack.once.cleanup.enabled=true", "spring.threads.virtual.enabled=true")
            .run { context ->
                assertThat(context).hasNotFailed()
                assertThat(
                    context.getBean(OnceCleanupScheduler.SCHEDULER_NAME),
                ).isInstanceOf(SimpleAsyncTaskScheduler::class.java)
                assertThat(context.getBean(OnceCleanupScheduler::class.java).isRunning).isTrue()
            }
    }

    @Test
    fun `invalid cleanup settings fail startup before cleanup is scheduled`() {
        listOf(
            "interval=0ms",
            "interval=1ns",
            "batch-size=0",
            "batch-size=100001",
            "shutdown-timeout=-1s",
        ).forEach { invalidSetting ->
            runner
                .withPropertyValues("namastack.once.cleanup.enabled=true", "namastack.once.cleanup.$invalidSetting")
                .run { context ->
                    assertThat(context).hasFailed()
                    assertThat(context.startupFailure).hasRootCauseInstanceOf(IllegalArgumentException::class.java)
                }
        }
    }
}
