package io.namastack.once

import java.util.function.Function

/**
 * Normal outcome of a protected invocation; execution failures are propagated as exceptions.
 *
 * No previous value is available for a duplicate. Once returns [Executed] after committing its
 * transaction. Use [fold] from Java or an exhaustive `when` expression from Kotlin.
 *
 * @param T Type of the business value carried by an executed result.
 *
 * @author Roland Beisel
 * @since 0.1.0
 */
sealed interface IdempotencyResult<T> {
    /**
     * Reports whether this outcome is [Executed].
     *
     * Results returned by Once follow transaction completion. Constructing this wrapper inside
     * the callback does not itself commit the transaction.
     *
     * @return True for [Executed], false for [Duplicate].
     */
    fun wasExecuted(): Boolean

    /**
     * Maps either normal outcome to an application-specific response.
     *
     * Only the matching function is invoked. Duplicate handling receives invocation metadata,
     * because Once does not persist or replay a previously computed business value.
     *
     * @param R Type of the mapped application response.
     * @param onExecuted Maps the value returned by a newly executed action.
     * @param onDuplicate Maps the context of an invocation skipped as a duplicate.
     * @return The value produced by the function matching this outcome.
     */
    fun <R> fold(
        onExecuted: Function<in T, out R>,
        onDuplicate: Function<in IdempotencyDuplicateContext, out R>,
    ): R

    /**
     * The new business value. Once returns this outcome after committing the action and marker.
     *
     * @param T Type of the newly computed business value.
     * @property value The newly computed value, never a replay of an earlier invocation.
     *
     * @author Roland Beisel
     * @since 0.1.0
     */
    @JvmRecord
    data class Executed<T>(
        val value: T,
    ) : IdempotencyResult<T> {
        /**
         * Identifies this outcome as an action that returned normally.
         *
         * @return True for this executed outcome.
         */
        override fun wasExecuted(): Boolean = true

        /**
         * Maps the newly computed business value through the execution branch.
         *
         * @param R Type of the mapped application response.
         * @param onExecuted Function invoked with this outcome's value.
         * @param onDuplicate Unused duplicate branch retained by the shared result contract.
         * @return The response produced by the execution mapping.
         */
        override fun <R> fold(
            onExecuted: Function<in T, out R>,
            onDuplicate: Function<in IdempotencyDuplicateContext, out R>,
        ): R = onExecuted.apply(value)
    }

    /**
     * A skipped invocation protected by a stored marker.
     *
     * @param T Business value type shared with the corresponding executed result.
     * @property context Identity metadata for an application-specific duplicate decision.
     *
     * @author Roland Beisel
     * @since 0.1.0
     */
    @JvmRecord
    data class Duplicate<T>(
        val context: IdempotencyDuplicateContext,
    ) : IdempotencyResult<T> {
        /**
         * Identifies this outcome as a skipped application action.
         *
         * @return False for this duplicate outcome.
         */
        override fun wasExecuted(): Boolean = false

        /**
         * Maps the current invocation context through the duplicate branch.
         *
         * @param R Type of the mapped application response.
         * @param onExecuted Unused execution branch retained by the shared result contract.
         * @param onDuplicate Function invoked with this skipped invocation's context.
         * @return The response produced by the duplicate mapping.
         */
        override fun <R> fold(
            onExecuted: Function<in T, out R>,
            onDuplicate: Function<in IdempotencyDuplicateContext, out R>,
        ): R = onDuplicate.apply(context)
    }

    companion object {
        /**
         * Wraps the value produced by the current application action.
         *
         * The execution service validates this wrapper before transaction completion. Creating it
         * does not reserve an identity or certify a database commit by itself.
         *
         * @param T Type of the newly computed business value.
         * @param value Newly computed business value, which may be null.
         * @return An executed outcome carrying the supplied value.
         */
        @JvmStatic
        fun <T> executed(value: T): Executed<T> = Executed(value)

        /**
         * Completes an application callback that has no business value to return.
         *
         * Java callers can return this wrapper from an `IdempotencyResult<Void>` callback without
         * supplying an explicit null argument. The wrapper does not commit the transaction; Once
         * commits after the application action returns.
         *
         * @return An executed outcome with a null Void value.
         */
        @JvmStatic
        fun executed(): Executed<Void?> = Executed(null)
    }
}
