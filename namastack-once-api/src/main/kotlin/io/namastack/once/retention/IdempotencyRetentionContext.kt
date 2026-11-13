package io.namastack.once.retention

/**
 * Bounded invocation metadata supplied to a retention policy; it excludes raw keys and payloads.
 *
 * @property scope Stable namespace in which invocation keys are unique.
 *
 * @author Roland Beisel
 * @since 0.1.0
 */
@JvmRecord
data class IdempotencyRetentionContext(
    val scope: String,
)
