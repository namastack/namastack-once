package io.namastack.once

import io.namastack.once.IdempotencyResult.Duplicate
import io.namastack.once.IdempotencyResult.Executed
import java.util.function.Supplier

/**
 * Executes synchronous application operations with transactional duplicate protection.
 *
 * Marker and business writes must use the instance's local transaction resource. A callback returns
 * [Executed]; only Once determines [Duplicate]. Once owns the transaction and returns only after
 * completion. Action failures roll back before propagating; calls inside an existing transaction
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
     * Callbacks must return [Executed], including [IdempotencyResult.executed] without a business
     * value. Null wrappers and caller-supplied duplicate results are rejected before commit.
     * Commit failures propagate without removing a marker that may already have been committed.
     *
     * ```kotlin
     * val result = once.execute(IdempotencyRequest.of("order.create", command.requestId)) {
     *     IdempotencyResult.executed(orderService.create(command))
     * }
     * ```
     *
     * @param T Type of the newly computed business value; Java no-value actions use Void.
     * @param request Stable scope/key identity and retention policy.
     * @param action Synchronous work returning [IdempotencyResult.executed], including for no-value work.
     * @return A flat [Executed] or [Duplicate]; never a replay of a previous value.
     * @throws IllegalArgumentException if the identity or retention policy is invalid.
     * @throws IllegalStateException if a transaction is already active, ownership checks fail, or the callback result is invalid.
     */
    fun <T> execute(
        request: IdempotencyRequest,
        action: Supplier<IdempotencyResult<T>>,
    ): IdempotencyResult<T>
}
