package io.namastack.once.observability

import io.micrometer.observation.ObservationRegistry
import io.namastack.once.DefaultIdempotencyOperations
import io.namastack.once.config.OnceCoreInfrastructureAutoConfiguration
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.micrometer.observation.autoconfigure.ObservationAutoConfiguration
import org.springframework.context.annotation.Bean

/** Optional Micrometer adapter; Core remains usable without Micrometer. @since 0.1.0 */
@AutoConfiguration(
    after = [ObservationAutoConfiguration::class],
    before = [OnceCoreInfrastructureAutoConfiguration::class],
)
@ConditionalOnClass(DefaultIdempotencyOperations::class, ObservationRegistry::class)
@ConditionalOnProperty(name = ["namastack.once.enabled"], havingValue = "true", matchIfMissing = true)
class OnceObservabilityAutoConfiguration {
    /**
     * Supplies instrumentation before persistence auto-configuration composes its interceptors.
     * @param registry Shared application observation registry.
     * @param executeConvention Optional custom execution convention.
     * @param cleanupConvention Optional custom cleanup convention.
     * @return The default Micrometer adapter unless supplied by the application.
     */
    @Bean
    @ConditionalOnBean(ObservationRegistry::class)
    @ConditionalOnMissingBean(MicrometerOnceInstrumentation::class)
    fun micrometerOnceInstrumentation(
        registry: ObjectProvider<ObservationRegistry>,
        executeConvention: ObjectProvider<OnceExecuteObservationConvention>,
        cleanupConvention: ObjectProvider<OnceCleanupObservationConvention>,
    ): MicrometerOnceInstrumentation =
        MicrometerOnceInstrumentation(
            observationRegistrySupplier = registry::getObject,
            executeConventionSupplier = executeConvention::getIfAvailable,
            cleanupConventionSupplier = cleanupConvention::getIfAvailable,
        )
}
