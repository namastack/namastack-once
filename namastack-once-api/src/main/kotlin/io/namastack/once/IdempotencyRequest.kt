package io.namastack.once

import io.namastack.once.retention.ForeverIdempotencyRetentionPolicy
import io.namastack.once.retention.IdempotencyRetentionPolicy

/**
 * Immutable identity and retention options for a configured Once instance.
 *
 * @property scope Stable, non-blank key namespace, at most 200 UTF-16 characters.
 * @property key Non-null application key, converted to a canonical non-blank string before execution.
 * @property retentionPolicy Computes the earliest cleanup time for new reservations; [of] selects unlimited retention.
 * @throws IllegalArgumentException if the scope is blank or exceeds 200 UTF-16 characters.
 *
 * @author Roland Beisel
 * @since 0.1.0
 */
@JvmRecord
data class IdempotencyRequest(
    val scope: String,
    val key: Any,
    val retentionPolicy: IdempotencyRetentionPolicy,
) {
    init {
        require(scope.isNotBlank()) { "Idempotency scope must not be blank" }
        require(scope.length <= 200) { "Idempotency scope must not exceed 200 characters" }
    }

    /**
     * Selects a retention policy for newly reserved markers without changing this request.
     *
     * @param policy Policy used to calculate the next reservation's earliest cleanup time.
     * @return A new request preserving the scope and key.
     */
    fun withRetentionPolicy(policy: IdempotencyRetentionPolicy): IdempotencyRequest = copy(retentionPolicy = policy)

    companion object {
        /**
         * Creates an invocation request using unlimited retention.
         *
         * @param scope Stable non-blank key namespace, at most 200 UTF-16 characters.
         * @param key Application key converted to a stable string before reservation.
         * @return The immutable request with unlimited retention.
         * @throws IllegalArgumentException if the scope is blank or exceeds its storage limit.
         */
        @JvmStatic
        fun of(
            scope: String,
            key: Any,
        ): IdempotencyRequest =
            IdempotencyRequest(
                scope = scope,
                key = key,
                retentionPolicy = ForeverIdempotencyRetentionPolicy,
            )
    }
}
