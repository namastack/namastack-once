package io.namastack.once

import io.namastack.once.IdempotencyResult.Duplicate
import io.namastack.once.IdempotencyResult.Executed
import java.util.function.Supplier

/**
 * Executes synchronous application operations with transactional duplicate protection.
 *
 * Marker and business writes must use the instance's local transaction resource. A callback returns
 * a business value; Once determines [Executed] or [Duplicate]. Once owns the transaction and returns
 * only after completion. Action failures roll back before propagating; calls inside an existing transaction
 * are rejected before marker reservation or application work.
 *
 * @author Roland Beisel
 * @since 0.1.0
 */
interface IdempotencyOperations {
    /**
     * Executes [action] for a new identity or returns a duplicate without invoking it.
     *
     * Marker reservation and business changes share one new transaction on the selected datasource.
     * Successful execution returns after commit. An action failure rolls back and propagates.
     * Existing transactions, including nested Once calls and completion callbacks, are rejected.
     * Business services called by the action may join the transaction owned by Once.
     *
     * The callback returns a business value, which may be null. Once wraps it in [Executed].
     * Returned result objects are business values too and are wrapped without special treatment.
     * Use [executeWithoutResult] for actions without a business value.
     * Commit failures propagate without removing a marker that may already have been committed.
     *
     * ```kotlin
     * val result = once.execute(IdempotencyRequest.of("order.create", command.requestId)) {
     *     orderService.create(command)
     * }
     * ```
     *
     * @param T Type of the newly computed business value.
     * @param request Stable scope/key identity and retention policy.
     * @param action Synchronous work returning the newly computed business value.
     * @return A flat [Executed] or [Duplicate]; never a replay of a previous value.
     * @throws IllegalArgumentException if the identity or retention policy is invalid.
     * @throws IllegalStateException if a transaction is already active or ownership checks fail.
     */
    fun <T> execute(
        request: IdempotencyRequest,
        action: Supplier<T>,
    ): IdempotencyResult<T>

    /**
     * Executes [action] without a business value, or skips it for a duplicate identity.
     *
     * Uses the same transaction and duplicate protection as [execute]. Successful execution
     * returns an [Executed] with a null value after commit; duplicates retain their context.
     *
     * @param request Stable scope/key identity and retention policy.
     * @param action Synchronous work without a business value.
     * @return An [Executed] with a null value or a [Duplicate].
     */
    fun executeWithoutResult(
        request: IdempotencyRequest,
        action: Runnable,
    ): IdempotencyResult<Void?>
}
