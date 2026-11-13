package io.namastack.once

/**
 * Invocation metadata for a skipped action, not data loaded from a previous execution.
 *
 * The original key is available only in memory. Once never logs it automatically.
 *
 * @property scope Stable namespace in which invocation keys are unique.
 * @property key Original application key; potentially sensitive.
 * @property keyHash SHA-256 identity digest, stored as lowercase hexadecimal.
 *
 * @author Roland Beisel
 * @since 0.1.0
 */
@JvmRecord
data class IdempotencyDuplicateContext(
    val scope: String,
    val key: Any,
    val keyHash: String,
)
