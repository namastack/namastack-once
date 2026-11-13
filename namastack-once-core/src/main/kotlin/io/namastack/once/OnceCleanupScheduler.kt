package io.namastack.once

import io.namastack.once.config.OnceCleanupProperties
import org.slf4j.LoggerFactory
import org.springframework.context.SmartLifecycle
import org.springframework.scheduling.TaskScheduler
import org.springframework.scheduling.support.PeriodicTrigger
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Schedules bounded marker cleanup through a Spring-managed task scheduler.
 *
 * Each pass drains configured repositories with a fixed expiry cutoff and an independent transaction
 * per batch. Lifecycle coordination prevents overlapping passes and lets an active batch finish
 * during shutdown. The application does not need to enable annotation-based scheduling.
 *
 * @param repositories Marker repositories belonging to the configured Once instances.
 * @param properties Cleanup interval, batch limit, and graceful shutdown timeout.
 * @param clock Supplies the fixed expiry cutoff used by each cleanup pass.
 * @param taskScheduler Spring-managed scheduler used to register the cleanup task.
 *
 * @author Roland Beisel
 * @since 0.1.0
 */
class OnceCleanupScheduler(
    private val repositories: List<IdempotencyMarkerRepository>,
    private val properties: OnceCleanupProperties,
    private val clock: Clock,
    private val taskScheduler: TaskScheduler,
) : SmartLifecycle {
    companion object {
        const val SCHEDULER_NAME: String = "onceCleanupScheduler"
    }

    private val logger = LoggerFactory.getLogger(javaClass)
    private val lifecycle = SchedulerLifecycleStateMachine(properties.shutdownTimeout)
    private var scheduledTask: ScheduledFuture<*>? = null

    /**
     * Reports whether cleanup is started, including an active pass awaiting shutdown.
     *
     * @return True until scheduling and any active pass have both stopped.
     */
    override fun isRunning(): Boolean = lifecycle.isStarted()

    /**
     * Registers cleanup once, with the first pass delayed by the configured interval.
     *
     * Scheduling uses the task scheduler's clock independently of the marker expiry clock.
     * A failed registration leaves the lifecycle stopped so startup can be retried.
     *
     * @throws IllegalStateException if the scheduler declines the cleanup task.
     */
    override fun start() {
        lifecycle.scheduleJob {
            val trigger = PeriodicTrigger(properties.interval).apply { setInitialDelay(properties.interval) }
            scheduledTask =
                checkNotNull(taskScheduler.schedule(::cleanup, trigger)) { "Once cleanup was not scheduled" }
        }
    }

    /**
     * Cancels future passes and waits up to the configured timeout for the active batch.
     *
     * The active cleanup operation is not interrupted. No further batch starts after shutdown
     * is requested, and the borrowed task scheduler remains under Spring's ownership.
     */
    override fun stop() {
        val completed =
            lifecycle.cancelScheduledJob {
                scheduledTask?.cancel(false)
                scheduledTask = null
            }
        if (!completed) logger.warn("Once cleanup is still completing an active batch after the shutdown wait")
    }

    /**
     * Drains configured repositories using one expiry cutoff sampled for the entire pass.
     *
     * Calls while stopped or while another pass is active are skipped. Repository failures are
     * logged and isolated so other repositories can continue; earlier committed batches remain deleted.
     */
    fun cleanup() {
        if (!lifecycle.startProcessing()) return

        try {
            cleanupRepositories()
        } catch (failure: Exception) {
            logger.error("Once cleanup pass failed", failure)
        } finally {
            lifecycle.stopProcessing()
        }
    }

    private fun cleanupRepositories() {
        val cutoff = clock.instant().toMarkerPrecision()
        for (repository in repositories) {
            if (!canContinueCleanup()) return
            cleanupRepositorySafely(repository, cutoff)
        }
    }

    private fun cleanupRepositorySafely(
        repository: IdempotencyMarkerRepository,
        cutoff: Instant,
    ) {
        try {
            val deletedRows = cleanupRepositoryInBatches(repository, cutoff)
            if (deletedRows > 0) {
                logger.debug(
                    "Once cleanup: repository={}, deleted={}",
                    repository,
                    deletedRows,
                )
            }
        } catch (failure: Exception) {
            logger.error("Once cleanup failed for repository={}", repository, failure)
        }
    }

    private fun cleanupRepositoryInBatches(
        repository: IdempotencyMarkerRepository,
        cutoff: Instant,
    ): Long {
        val batchSize = properties.batchSize
        var totalDeletedRows = 0L
        while (canContinueCleanup()) {
            val deletedRows = repository.cleanup(cutoff, batchSize)
            totalDeletedRows += deletedRows

            if (deletedRows == 0) return totalDeletedRows
        }

        return totalDeletedRows
    }

    private fun canContinueCleanup(): Boolean = lifecycle.isProcessing() && !Thread.currentThread().isInterrupted

    /**
     * Coordinates job registration, cleanup execution, and graceful shutdown.
     *
     * A timed-out stop retains the shutting-down state until the active pass finishes,
     * preventing a restart from admitting overlapping cleanup work.
     */
    private class SchedulerLifecycleStateMachine(
        private val shutdownTimeout: Duration,
    ) {
        private enum class LifecycleState {
            /** No scheduled task or active cleanup pass. */
            STOPPED,

            /** Scheduled and waiting for the next cleanup pass. */
            IDLE,

            /** A cleanup pass is active and may start another batch. */
            RUNNING,

            /** Scheduling is cancelled; the active batch is allowed to finish. */
            SHUTTING_DOWN,
        }

        private val lock = ReentrantLock()
        private val processingComplete = lock.newCondition()
        private var state = LifecycleState.STOPPED
        private var processingThread: Thread? = null

        fun isStarted(): Boolean = lock.withLock { state != LifecycleState.STOPPED }

        fun isProcessing(): Boolean = lock.withLock { state == LifecycleState.RUNNING }

        fun scheduleJob(schedule: () -> Unit): Unit =
            lock.withLock {
                if (state != LifecycleState.STOPPED) return
                schedule()
                state = LifecycleState.IDLE
            }

        fun startProcessing(): Boolean =
            lock.withLock {
                if (state != LifecycleState.IDLE) return false
                processingThread = Thread.currentThread()
                state = LifecycleState.RUNNING
                true
            }

        fun stopProcessing(): Unit =
            lock.withLock {
                processingThread = null
                state =
                    when (state) {
                        LifecycleState.RUNNING -> LifecycleState.IDLE
                        LifecycleState.SHUTTING_DOWN -> LifecycleState.STOPPED
                        else -> state
                    }
                processingComplete.signalAll()
            }

        fun cancelScheduledJob(cancel: () -> Unit): Boolean =
            lock.withLock {
                when (state) {
                    LifecycleState.STOPPED -> return true

                    LifecycleState.IDLE -> {
                        state = LifecycleState.SHUTTING_DOWN
                        cancel()
                        state = LifecycleState.STOPPED
                        return true
                    }

                    LifecycleState.RUNNING, LifecycleState.SHUTTING_DOWN -> {
                        state = LifecycleState.SHUTTING_DOWN
                        cancel()
                        return awaitProcessingComplete()
                    }
                }
            }

        /** Called with the lock held; condition waits release it so the active pass can finish. */
        private fun awaitProcessingComplete(): Boolean {
            // Cleanup may request its own shutdown; waiting here would deadlock that pass.
            if (processingThread === Thread.currentThread()) return true

            var remainingNanos = shutdownTimeout.toNanos()
            try {
                while (state == LifecycleState.SHUTTING_DOWN && remainingNanos > 0) {
                    remainingNanos = processingComplete.awaitNanos(remainingNanos)
                }
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }

            return state == LifecycleState.STOPPED
        }
    }
}
