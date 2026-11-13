package io.namastack.once.config

import io.namastack.once.IdempotencyOperations
import io.namastack.once.OnceCleanupScheduler.Companion.SCHEDULER_NAME
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.autoconfigure.condition.ConditionalOnThreading
import org.springframework.boot.autoconfigure.task.TaskSchedulingAutoConfiguration
import org.springframework.boot.task.SimpleAsyncTaskSchedulerBuilder
import org.springframework.boot.task.ThreadPoolTaskSchedulerBuilder
import org.springframework.boot.thread.Threading
import org.springframework.context.annotation.Bean
import org.springframework.scheduling.concurrent.SimpleAsyncTaskScheduler
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler

/**
 * Provides a dedicated Spring-managed cleanup scheduler matching Boot's threading configuration.
 *
 * The named scheduler can be replaced by the application and is excluded from autowiring so it
 * does not become the application's default scheduler.
 *
 * @author Roland Beisel
 * @since 0.1.0
 */
@AutoConfiguration(after = [OnceCoreInfrastructureAutoConfiguration::class, TaskSchedulingAutoConfiguration::class])
@ConditionalOnBean(IdempotencyOperations::class)
@ConditionalOnProperty(
    name = ["namastack.once.enabled", "namastack.once.cleanup.enabled"],
    havingValue = "true",
    matchIfMissing = true,
)
class OnceCoreThreadingAutoConfiguration {
    /**
     * Builds the single-thread scheduler used when platform threads are enabled.
     *
     * @param builder Boot builder applying application task-scheduling customizations.
     * @return Dedicated cleanup scheduler initialized and destroyed by Spring.
     */
    @Bean(SCHEDULER_NAME, autowireCandidate = false)
    @ConditionalOnMissingBean(name = [SCHEDULER_NAME])
    @ConditionalOnThreading(Threading.PLATFORM)
    fun onceCleanupScheduler(builder: ThreadPoolTaskSchedulerBuilder): ThreadPoolTaskScheduler =
        builder.poolSize(1).threadNamePrefix("once-cleanup-").build()

    /**
     * Builds a scheduler dispatching cleanup passes to virtual threads.
     *
     * @param builder Boot builder applying application virtual-thread customizations.
     * @return Dedicated scheduler admitting one cleanup task at a time.
     */
    @Bean(SCHEDULER_NAME, autowireCandidate = false)
    @ConditionalOnMissingBean(name = [SCHEDULER_NAME])
    @ConditionalOnThreading(Threading.VIRTUAL)
    fun onceCleanupSchedulerVirtualThreads(builder: SimpleAsyncTaskSchedulerBuilder): SimpleAsyncTaskScheduler =
        builder.concurrencyLimit(1).threadNamePrefix("once-cleanup-").build()
}
