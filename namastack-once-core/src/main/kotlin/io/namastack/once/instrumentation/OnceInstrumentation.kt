package io.namastack.once.instrumentation

import io.namastack.once.IdempotencyResult
import java.time.Instant

/**
 * Observes Once execution and committed cleanup batches without changing their behavior.
 * Implementations must invoke each action exactly once and preserve its result or exception.
 * @since 0.1.0
 */
interface OnceInstrumentation {
    /**
     * Wraps validation, reservation, protected work, and transaction completion.
     * @param T Business result type.
     * @param scope Application's configured identity namespace; never the application key.
     * @param action Complete execution, returning only after transaction completion.
     * @return The unchanged execution result.
     */
    fun <T> execute(
        scope: String,
        action: () -> IdempotencyResult<T>,
    ): IdempotencyResult<T> = action()

    /**
     * Wraps one independent cleanup transaction, including commit.
     * @param backend Persistence adapter, such as jdbc or mongodb.
     * @param cutoff Inclusive expiry cutoff.
     * @param batchSize Maximum deletions requested.
     * @param action Batch operation returning its committed deletion count.
     * @return The unchanged committed deletion count.
     */
    fun cleanup(
        backend: String,
        cutoff: Instant,
        batchSize: Int,
        action: () -> Int,
    ): Int = action()

    companion object {
        /** Instrumentation that executes actions without adding behavior. */
        @JvmField
        val NOOP: OnceInstrumentation = object : OnceInstrumentation {}

        /**
         * Composes interceptors in list order, with the first outermost.
         * @param instrumentations Ordered instrumentation implementations.
         * @return A no-op for an empty list, otherwise an immutable composition.
         */
        @JvmStatic
        fun compose(instrumentations: List<OnceInstrumentation>): OnceInstrumentation {
            val entries = instrumentations.toList()
            return entries.foldRight(NOOP) { current, next ->
                object : OnceInstrumentation {
                    override fun <T> execute(
                        scope: String,
                        action: () -> IdempotencyResult<T>,
                    ): IdempotencyResult<T> = current.execute(scope) { next.execute(scope, action) }

                    override fun cleanup(
                        backend: String,
                        cutoff: Instant,
                        batchSize: Int,
                        action: () -> Int,
                    ): Int =
                        current.cleanup(backend, cutoff, batchSize) { next.cleanup(backend, cutoff, batchSize, action) }
                }
            }
        }
    }
}
