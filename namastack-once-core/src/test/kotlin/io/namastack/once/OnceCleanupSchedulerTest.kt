package io.namastack.once

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.namastack.once.config.OnceCleanupProperties
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import org.springframework.scheduling.TaskScheduler
import org.springframework.scheduling.Trigger
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit.SECONDS

class OnceCleanupSchedulerTest {
    private val cutoff = Instant.parse("2026-11-12T10:00:00Z")
    private val cleanupServices = mutableListOf<OnceCleanupScheduler>()
    private val scheduler = mockk<TaskScheduler>()
    private val scheduledTask = mockk<ScheduledFuture<*>>(relaxed = true)

    @AfterEach
    fun stopCleanupServices() {
        cleanupServices.forEach(OnceCleanupScheduler::stop)
    }

    @Test
    fun `cleanup continues after a partial batch until the database reports zero deletions`() {
        val repository = mockk<IdempotencyMarkerRepository>()
        val cleanup = createCleanup(repository)
        every { repository.cleanup(cutoff, 2) } returnsMany listOf(2, 1, 0)

        cleanup.cleanup()

        verify(exactly = 3) { repository.cleanup(cutoff, 2) }
    }

    @Test
    fun `shutdown stops the pass between batches and prevents later cleanup work`() {
        val repository = mockk<IdempotencyMarkerRepository>()
        val cleanup = createCleanup(repository)
        every { repository.cleanup(cutoff, 2) } answers {
            cleanup.stop()
            2
        }

        cleanup.cleanup()
        cleanup.cleanup()

        verify(exactly = 1) { repository.cleanup(cutoff, 2) }
        verify(exactly = 1) { scheduledTask.cancel(false) }
        assertThat(cleanup.isRunning).isFalse()
    }

    @Test
    fun `start is idempotent and a stopped cleanup can be restarted`() {
        val cleanup = createCleanup(mockk())

        cleanup.start()
        verify(exactly = 1) { scheduler.schedule(any<Runnable>(), any<Trigger>()) }
        cleanup.stop()
        cleanup.start()

        assertThat(cleanup.isRunning).isTrue()
        verify(exactly = 2) { scheduler.schedule(any<Runnable>(), any<Trigger>()) }
    }

    @Test
    fun `a failed scheduling attempt leaves cleanup stopped and can be retried`() {
        val cleanup = createCleanup(mockk(), start = false)
        every { scheduler.schedule(any<Runnable>(), any<Trigger>()) } throws IllegalStateException("rejected")

        assertThatThrownBy { cleanup.start() }.isInstanceOf(IllegalStateException::class.java)
        assertThat(cleanup.isRunning).isFalse()
        every { scheduler.schedule(any<Runnable>(), any<Trigger>()) } returns scheduledTask
        cleanup.start()
        assertThat(cleanup.isRunning).isTrue()
    }

    @Test
    fun `overlapping passes are skipped and a timed out stop cannot restart an active pass`() {
        val repository = mockk<IdempotencyMarkerRepository>()
        val cleanup = createCleanup(repository, shutdownTimeout = Duration.ZERO)
        val batchStarted = CountDownLatch(1)
        val releaseBatch = CountDownLatch(1)
        val executor = Executors.newSingleThreadExecutor()
        every { repository.cleanup(cutoff, 2) } answers {
            batchStarted.countDown()
            check(releaseBatch.await(10, SECONDS))
            assertThat(Thread.currentThread().isInterrupted).isFalse()
            2
        }

        try {
            val activePass = executor.submit { cleanup.cleanup() }
            check(batchStarted.await(10, SECONDS))
            cleanup.cleanup()
            cleanup.stop()
            cleanup.start()
            cleanup.cleanup()
            verify(exactly = 1) { repository.cleanup(cutoff, 2) }
            verify(exactly = 1) { scheduler.schedule(any<Runnable>(), any<Trigger>()) }
            releaseBatch.countDown()
            activePass.get(10, SECONDS)
            assertThat(cleanup.isRunning).isFalse()
            cleanup.start()
            assertThat(cleanup.isRunning).isTrue()
        } finally {
            releaseBatch.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    fun `graceful stop waits for the active batch and cancels scheduling without interruption`() {
        val repository = mockk<IdempotencyMarkerRepository>()
        val cleanup = createCleanup(repository)
        val batchStarted = CountDownLatch(1)
        val releaseBatch = CountDownLatch(1)
        val cancellationRequested = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)
        every { scheduledTask.cancel(false) } answers {
            cancellationRequested.countDown()
            true
        }
        every { repository.cleanup(cutoff, 2) } answers {
            batchStarted.countDown()
            check(releaseBatch.await(10, SECONDS))
            assertThat(Thread.currentThread().isInterrupted).isFalse()
            2
        }

        try {
            val activePass = executor.submit { cleanup.cleanup() }
            check(batchStarted.await(10, SECONDS))
            val shutdown = executor.submit { cleanup.stop() }
            check(cancellationRequested.await(10, SECONDS))
            assertThat(shutdown.isDone).isFalse()
            releaseBatch.countDown()
            activePass.get(10, SECONDS)
            shutdown.get(10, SECONDS)
            assertThat(cleanup.isRunning).isFalse()
            verify(exactly = 1) { repository.cleanup(cutoff, 2) }
        } finally {
            releaseBatch.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    fun `one store failure does not prevent cleanup of the remaining stores`() {
        val firstRepository = mockk<IdempotencyMarkerRepository>()
        val secondRepository = mockk<IdempotencyMarkerRepository>()

        every { firstRepository.cleanup(cutoff, 2) } throws IllegalStateException("unavailable")
        every { secondRepository.cleanup(cutoff, 2) } returns 0
        val cleanup = createCleanup(firstRepository, repositories = listOf(firstRepository, secondRepository))

        cleanup.cleanup()

        verify(exactly = 1) { secondRepository.cleanup(cutoff, 2) }
    }

    @Test
    fun `cleanup identifies each repository in failure and success logs`() {
        val firstRepository = mockk<IdempotencyMarkerRepository>()
        val secondRepository = mockk<IdempotencyMarkerRepository>()
        val failure = IllegalStateException("unavailable")
        every { firstRepository.toString() } returns "orders_repository"
        every { secondRepository.toString() } returns "billing_repository"
        every { firstRepository.cleanup(cutoff, 2) } throws failure
        every { secondRepository.cleanup(cutoff, 2) } returnsMany listOf(1, 0)
        val cleanup = createCleanup(firstRepository, repositories = listOf(firstRepository, secondRepository))
        val logger = LoggerFactory.getLogger(OnceCleanupScheduler::class.java) as Logger
        val previousLevel = logger.level
        val appender = ListAppender<ILoggingEvent>().apply { start() }
        logger.addAppender(appender)
        logger.level = Level.DEBUG

        try {
            cleanup.cleanup()

            assertThat(appender.list.map { it.formattedMessage }).containsExactly(
                "Once cleanup failed for repository=orders_repository",
                "Once cleanup: repository=billing_repository, deleted=1",
            )
            assertThat(
                appender.list
                    .first()
                    .throwableProxy.message,
            ).isEqualTo(failure.message)
        } finally {
            logger.detachAppender(appender)
            logger.level = previousLevel
            appender.stop()
        }
    }

    private fun createCleanup(
        repository: IdempotencyMarkerRepository,
        start: Boolean = true,
        shutdownTimeout: Duration = Duration.ofSeconds(30),
        repositories: List<IdempotencyMarkerRepository> = listOf(repository),
    ): OnceCleanupScheduler {
        every { scheduler.schedule(any<Runnable>(), any<Trigger>()) } returns scheduledTask
        val cleanup =
            OnceCleanupScheduler(
                repositories,
                OnceCleanupProperties(batchSize = 2, shutdownTimeout = shutdownTimeout),
                Clock.fixed(cutoff, ZoneOffset.UTC),
                scheduler,
            )
        cleanupServices.add(cleanup)
        if (start) cleanup.start()
        return cleanup
    }
}
