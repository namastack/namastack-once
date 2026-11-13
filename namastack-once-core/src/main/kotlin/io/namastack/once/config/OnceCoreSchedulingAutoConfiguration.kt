package io.namastack.once.config

import io.namastack.once.DefaultIdempotencyOperations
import io.namastack.once.IdempotencyMarkerRepository
import io.namastack.once.IdempotencyOperations
import io.namastack.once.OnceCleanupScheduler
import org.springframework.beans.factory.BeanFactory
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.getBean
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.scheduling.TaskScheduler
import java.time.Clock

/**
 * Connects cleanup scheduling to persistence-neutral marker repositories.
 *
 * Threading configuration owns the named task scheduler. This configuration supplies the cleanup
 * lifecycle only when the application exposes configured Once instances and cleanup is enabled.
 * Repositories are collected from DefaultIdempotencyOperations instances and deduplicated before
 * scheduling; custom IdempotencyOperations implementations do not contribute repositories.
 *
 * @author Roland Beisel
 * @since 0.1.0
 */
@AutoConfiguration(after = [OnceCoreInfrastructureAutoConfiguration::class, OnceCoreThreadingAutoConfiguration::class])
@ConditionalOnBean(IdempotencyOperations::class)
@ConditionalOnProperty(
    name = ["namastack.once.enabled", "namastack.once.cleanup.enabled"],
    havingValue = "true",
    matchIfMissing = true,
)
@EnableConfigurationProperties(OnceCleanupProperties::class)
class OnceCoreSchedulingAutoConfiguration {
    /**
     * Creates the cleanup lifecycle using the dedicated scheduler bean.
     *
     * @param instances Configured Once beans supplying their fixed marker repositories.
     * @param properties Cleanup interval, batch limit, and graceful shutdown timeout.
     * @param clock Clock shared with marker reservation timestamps.
     * @param beanFactory Registry containing the named task scheduler.
     * @return Persistence-independent scheduler unless the application provides its own instance.
     */
    @Bean("onceCleanup")
    @ConditionalOnMissingBean
    fun onceCleanupScheduler(
        instances: ObjectProvider<IdempotencyOperations>,
        properties: OnceCleanupProperties,
        clock: Clock,
        beanFactory: BeanFactory,
    ): OnceCleanupScheduler =
        OnceCleanupScheduler(
            repositories = collectRepositories(instances),
            properties = properties,
            clock = clock,
            taskScheduler = beanFactory.getBean<TaskScheduler>(OnceCleanupScheduler.SCHEDULER_NAME),
        )

    private fun collectRepositories(
        instances: ObjectProvider<IdempotencyOperations>,
    ): List<IdempotencyMarkerRepository> =
        instances
            .orderedStream()
            .toList()
            .filterIsInstance<DefaultIdempotencyOperations>()
            .map { it.repository }
            .distinct()
}
