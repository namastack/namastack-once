package io.namastack.once.retention

import java.time.Instant
import java.util.Optional

/**
 * Default policy retaining markers until the application explicitly removes them.
 *
 * @author Roland Beisel
 * @since 0.1.0
 */
object ForeverIdempotencyRetentionPolicy : IdempotencyRetentionPolicy {
    /**
     * Selects unlimited retention independently of the invocation's scope and creation time.
     *
     * @param context Invocation metadata, unused by this policy.
     * @param createdAt Reservation time, unused because no expiry is calculated.
     * @return An empty Optional, leaving marker removal to explicit application maintenance.
     */
    override fun calculateExpiry(
        context: IdempotencyRetentionContext,
        createdAt: Instant,
    ): Optional<Instant> = Optional.empty()
}
