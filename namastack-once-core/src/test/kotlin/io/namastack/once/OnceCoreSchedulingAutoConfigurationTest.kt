package io.namastack.once

import io.mockk.mockk
import io.namastack.once.config.OnceCleanupProperties
import io.namastack.once.config.OnceCoreSchedulingAutoConfiguration
import io.namastack.once.config.OnceCoreThreadingAutoConfiguration
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.autoconfigure.task.TaskSchedulingAutoConfiguration
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit.MICROS
import java.util.function.Supplier

class OnceCoreSchedulingAutoConfigurationTest {
    private val cutoff = Instant.parse("2026-11-12T10:00:00.123456789Z")
    private val runner =
        ApplicationContextRunner()
            .withConfiguration(
                AutoConfigurations.of(
                    TaskSchedulingAutoConfiguration::class.java,
                    OnceCoreThreadingAutoConfiguration::class.java,
                    OnceCoreSchedulingAutoConfiguration::class.java,
                ),
            ).withBean(Clock::class.java, Supplier { Clock.fixed(cutoff, ZoneOffset.UTC) })

    @Test
    fun `core schedules cleanup against a persistence port without JDBC on the classpath`() {
        val repository = RecordingMarkerRepository()
        withRepository(repository).run { context ->
            assertThat(context).hasNotFailed()
            assertThat(context).hasSingleBean(OnceCleanupScheduler::class.java)
            assertThat(
                context.getBean(OnceCleanupScheduler.SCHEDULER_NAME),
            ).isInstanceOf(ThreadPoolTaskScheduler::class.java)

            context.getBean(OnceCleanupScheduler::class.java).cleanup()

            assertThat(repository.cutoffs).containsExactlyElementsOf(List(3) { cutoff.truncatedTo(MICROS) })
            assertThat(repository.batchSizes).containsExactly(1000, 1000, 1000)
            assertThat(repository.remainingMarkers).isZero()
        }
    }

    @Test
    fun `cleanup binds configured values through the properties constructor`() {
        val repository = RecordingMarkerRepository()
        withRepository(repository)
            .withPropertyValues(
                "namastack.once.cleanup.interval=2m",
                "namastack.once.cleanup.batch-size=600",
                "namastack.once.cleanup.shutdown-timeout=3s",
            ).run { context ->
                assertThat(context).hasNotFailed()
                val properties = context.getBean(OnceCleanupProperties::class.java)
                assertThat(properties.interval).isEqualTo(Duration.ofMinutes(2))
                assertThat(properties.batchSize).isEqualTo(600)
                assertThat(properties.shutdownTimeout).isEqualTo(Duration.ofSeconds(3))

                context.getBean(OnceCleanupScheduler::class.java).cleanup()

                assertThat(repository.batchSizes).containsExactly(600, 600, 600, 600)
                assertThat(repository.remainingMarkers).isZero()
            }
    }

    @Test
    fun `core does not create cleanup infrastructure without a Once instance`() {
        runner.run { context ->
            assertThat(context).hasNotFailed()
            assertThat(context).doesNotHaveBean(OnceCleanupScheduler::class.java)
            assertThat(context).doesNotHaveBean(OnceCleanupScheduler.SCHEDULER_NAME)
        }
    }

    @Test
    fun `disabling Once or cleanup prevents scheduler creation`() {
        listOf("namastack.once.enabled=false", "namastack.once.cleanup.enabled=false").forEach { property ->
            withRepository(RecordingMarkerRepository()).withPropertyValues(property).run { context ->
                assertThat(context).hasNotFailed()
                assertThat(context).doesNotHaveBean(OnceCleanupScheduler::class.java)
                assertThat(context).doesNotHaveBean(OnceCleanupScheduler.SCHEDULER_NAME)
            }
        }
    }

    @Test
    fun `application cleanup lifecycle replaces the auto configured lifecycle`() {
        val scheduler = ThreadPoolTaskScheduler().apply { initialize() }
        val customCleanup =
            OnceCleanupScheduler(emptyList(), OnceCleanupProperties(), Clock.fixed(cutoff, ZoneOffset.UTC), scheduler)
        try {
            withRepository(RecordingMarkerRepository())
                .withBean("customCleanup", OnceCleanupScheduler::class.java, Supplier { customCleanup })
                .run { context ->
                    assertThat(context).hasNotFailed()
                    assertThat(context).hasSingleBean(OnceCleanupScheduler::class.java)
                    assertThat(context.getBean(OnceCleanupScheduler::class.java)).isSameAs(customCleanup)
                }
        } finally {
            scheduler.destroy()
        }
    }

    private fun withRepository(repository: RecordingMarkerRepository): ApplicationContextRunner {
        val once =
            DefaultIdempotencyOperations(
                mockk(),
                repository,
                IdempotencyKeyNormalizer(),
                Clock.fixed(cutoff, ZoneOffset.UTC),
            )
        return runner.withBean("once", IdempotencyOperations::class.java, Supplier { once })
    }

    private class RecordingMarkerRepository : IdempotencyMarkerRepository {
        override val transactionResourceKey: Any = Any()
        val cutoffs = mutableListOf<Instant>()
        val batchSizes = mutableListOf<Int>()
        var remainingMarkers = 1500
            private set

        override fun reserve(marker: IdempotencyMarker): Boolean = error("Reservation is not used by cleanup")

        override fun cleanup(
            cutoff: Instant,
            batchSize: Int,
        ): Int {
            cutoffs.add(cutoff)
            batchSizes.add(batchSize)
            val batchDeletions = minOf(remainingMarkers, batchSize)
            remainingMarkers -= batchDeletions
            return batchDeletions
        }
    }
}
