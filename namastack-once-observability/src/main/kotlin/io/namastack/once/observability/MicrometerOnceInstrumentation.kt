package io.namastack.once.observability

import io.micrometer.observation.ObservationRegistry
import io.namastack.once.IdempotencyResult
import io.namastack.once.instrumentation.OnceInstrumentation
import java.time.Instant
import java.util.function.Supplier

/**
 * Observes Once operations with Micrometer, preserving results and exception identities.
 * Registry and conventions are resolved lazily, following Outbox's instrumentation pattern.
 * @param observationRegistrySupplier Shared application registry, resolved on first use.
 * @param executeConventionSupplier Optional application execution convention.
 * @param cleanupConventionSupplier Optional application cleanup convention.
 * @since 0.1.0
 */
class MicrometerOnceInstrumentation(
    observationRegistrySupplier: () -> ObservationRegistry,
    executeConventionSupplier: () -> OnceExecuteObservationConvention? = { null },
    cleanupConventionSupplier: () -> OnceCleanupObservationConvention? = { null },
) : OnceInstrumentation {
    /**
     * Creates instrumentation with an already resolved registry.
     * @param observationRegistry Shared application registry.
     * @param executeConventionSupplier Optional custom execution convention.
     * @param cleanupConventionSupplier Optional custom cleanup convention.
     */
    constructor(
        observationRegistry: ObservationRegistry,
        executeConventionSupplier: () -> OnceExecuteObservationConvention? = { null },
        cleanupConventionSupplier: () -> OnceCleanupObservationConvention? = { null },
    ) : this({ observationRegistry }, executeConventionSupplier, cleanupConventionSupplier)

    private val registry by lazy(observationRegistrySupplier)
    private val executeConvention by lazy(executeConventionSupplier)
    private val cleanupConvention by lazy(cleanupConventionSupplier)

    /**
     * Observes an invocation through transaction completion; duplicates are successful skips.
     * @param scope Application namespace.
     * @param action Full protected execution.
     * @return The exact execution result, after transaction completion.
     */
    override fun <T> execute(
        scope: String,
        action: () -> IdempotencyResult<T>,
    ): IdempotencyResult<T> {
        val context = OnceExecuteObservationContext(scope)
        val observation =
            OnceObservationDocumentation.ONCE_EXECUTE.observation(
                executeConvention,
                OnceObservationDocumentation.DefaultOnceExecuteObservationConvention(),
                { context },
                registry,
            )
        return observation.observe(
            Supplier {
                try {
                    action().also { context.outcome = if (it.wasExecuted()) "executed" else "duplicate" }
                } catch (failure: Throwable) {
                    context.outcome = "error"
                    throw failure
                }
            },
        )
    }

    /**
     * Observes one cleanup batch and records its count only after commit succeeds.
     * @param backend Persistence adapter.
     * @param cutoff Inclusive expiry cutoff.
     * @param batchSize Requested deletion limit.
     * @param action Complete cleanup transaction.
     * @return The unchanged committed deletion count.
     */
    override fun cleanup(
        backend: String,
        cutoff: Instant,
        batchSize: Int,
        action: () -> Int,
    ): Int {
        val context = OnceCleanupObservationContext(backend, cutoff, batchSize)
        val observation =
            OnceObservationDocumentation.ONCE_CLEANUP_BATCH.observation(
                cleanupConvention,
                OnceObservationDocumentation.DefaultOnceCleanupObservationConvention(),
                { context },
                registry,
            )
        return observation.observe(
            Supplier {
                try {
                    action().also {
                        context.deletedCount = it
                        context.outcome = "success"
                    }
                } catch (failure: Throwable) {
                    context.outcome = "error"
                    throw failure
                }
            },
        )
    }
}
