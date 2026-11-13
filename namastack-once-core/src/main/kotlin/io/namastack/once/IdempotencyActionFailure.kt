package io.namastack.once

/**
 * Carries a checked action failure through Spring's RuntimeException-based transaction callback.
 *
 * Once unwraps the original throwable after rollback, preserving the annotated method's
 * exception contract.
 *
 * @property original Application throwable restored after the transaction callback exits.
 *
 * @author Roland Beisel
 * @since 0.1.0
 */
internal class IdempotencyActionFailure(
    val original: Throwable,
) : RuntimeException(null, original, false, false)
